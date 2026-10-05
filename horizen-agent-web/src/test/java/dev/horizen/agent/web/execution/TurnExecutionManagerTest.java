package dev.horizen.agent.web.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.application.turn.TurnExecutionManager;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.SessionExecutionState;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class TurnExecutionManagerTest {
    @Test
    void detachingOneObserverDoesNotCancelBackgroundTurnAndTerminalTurnIsReleased() {
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        Sinks.Many<AgentRuntimeEvent> source = Sinks.many().replay().all();
        source.tryEmitNext(event(AgentRuntimeEvent.Type.TURN_STARTED, "turn-1", "session-1"));

        try (TurnExecutionManager manager = new TurnExecutionManager(runtime, 16)) {
            Flux<AgentRuntimeEvent> first =
                    manager.start(
                            request("turn-1", "session-1"),
                            source.asFlux(),
                            Duration.ofMinutes(1),
                            null);
            first.take(1).blockLast(Duration.ofSeconds(1));

            assertFalse(runtime.interrupted.get());
            source.tryEmitNext(event(AgentRuntimeEvent.Type.TURN_COMPLETED, "turn-1", "session-1"));
            source.tryEmitComplete();

            // publishOn 异步处理完成事件；应先等待托管执行完成信号，再断言新查询已无法附加到此 Turn。
            first.collectList().block(Duration.ofSeconds(1));

            assertThrows(
                    TurnExecutionManager.TurnNotAvailableException.class,
                    () ->
                            manager.subscribe("owner", "session-1", "turn-1")
                                    .collectList()
                                    .block(Duration.ofSeconds(1)));
        }
    }

    @Test
    void explicitCancelChecksTurnIdAndUsesAgentScopeInterrupt() {
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        Sinks.Many<AgentRuntimeEvent> source = Sinks.many().replay().all();
        source.tryEmitNext(event(AgentRuntimeEvent.Type.TURN_STARTED, "turn-1", "session-1"));

        try (TurnExecutionManager manager = new TurnExecutionManager(runtime, 16)) {
            Flux<AgentRuntimeEvent> events =
                    manager.start(
                            request("turn-1", "session-1"),
                            source.asFlux(),
                            Duration.ofMinutes(1),
                            null);

            assertEquals(
                    TurnExecutionManager.CancelResult.TURN_CHANGED,
                    manager.cancel("owner", "session-1", "turn-old"));
            assertFalse(runtime.interrupted.get());
            assertEquals(
                    TurnExecutionManager.CancelResult.CANCELLED,
                    manager.cancel("owner", "session-1", "turn-1"));
            assertTrue(runtime.interrupted.get());
            assertTrue(
                    events.collectList().block(Duration.ofSeconds(1)).stream()
                            .anyMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_CANCELLED));
        }
    }

    @Test
    void serverDeadlineStopsTurnEvenWithoutBrowserSubscriber() {
        AtomicBoolean nonBlockingCallback = new AtomicBoolean(true);
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        Sinks.Many<AgentRuntimeEvent> source = Sinks.many().replay().all();
        source.tryEmitNext(event(AgentRuntimeEvent.Type.TURN_STARTED, "turn-1", "session-1"));

        try (TurnExecutionManager manager = new TurnExecutionManager(runtime, 16)) {
            List<AgentRuntimeEvent> events =
                    manager.start(
                                    request("turn-1", "session-1"),
                                    source.asFlux(),
                                    Duration.ofMillis(20),
                                    event -> {
                                        if (event.getType()
                                                == AgentRuntimeEvent.Type.TURN_TIMED_OUT)
                                            nonBlockingCallback.set(
                                                    Schedulers.isInNonBlockingThread());
                                    })
                            .collectList()
                            .block(Duration.ofSeconds(1));

            assertTrue(runtime.timedOut.get());
            assertFalse(
                    nonBlockingCallback.get(),
                    "Timeout persistence must run on a blocking-safe worker");
            assertEquals(
                    AgentRuntimeEvent.Type.TURN_TIMED_OUT, events.get(events.size() - 1).getType());
        }
    }

    @Test
    void observerFailureTerminatesReplayInsteadOfSilentlyDroppingFollowingEvents() {
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        Sinks.Many<AgentRuntimeEvent> source = Sinks.many().replay().all();
        source.tryEmitNext(event(AgentRuntimeEvent.Type.TURN_STARTED, "turn-1", "session-1"));

        try (TurnExecutionManager manager = new TurnExecutionManager(runtime, 16)) {
            Flux<AgentRuntimeEvent> events =
                    manager.start(
                            request("turn-1", "session-1"),
                            source.asFlux(),
                            Duration.ofMinutes(1),
                            event -> {
                                if (event.getType()
                                        == AgentRuntimeEvent.Type.PRESENTATION_CREATED) {
                                    throw new IllegalStateException("persistence failed");
                                }
                            });
            source.tryEmitNext(
                    event(AgentRuntimeEvent.Type.PRESENTATION_CREATED, "turn-1", "session-1"));

            var received = events.collectList().block(Duration.ofSeconds(1));
            var failure = received.get(received.size() - 1);
            assertEquals(AgentRuntimeEvent.Type.TURN_FAILED, failure.getType());
            assertTrue(failure.getText().contains("异常"));
            assertFalse(failure.getText().contains("persistence failed"));
        }
    }

    @Test
    void unclassifiedExceptionIsVisibleEvenWhenFallbackPersistenceAlsoFails() {
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        try (TurnExecutionManager manager = new TurnExecutionManager(runtime)) {
            var received =
                    manager.start(
                                    request("turn-1", "session-1"),
                                    Flux.error(new IllegalStateException("synthetic secret")),
                                    Duration.ofSeconds(1),
                                    event -> {
                                        throw new IllegalStateException("database unavailable");
                                    })
                            .collectList()
                            .block(Duration.ofSeconds(3));
            assertEquals(1, received.size());
            assertEquals(AgentRuntimeEvent.Type.TURN_FAILED, received.get(0).getType());
            assertFalse(received.get(0).getText().contains("secret"));
            assertEquals(TurnStatus.FAILED, runtime.state.get().getStatus());
        }
    }

    @Test
    void emptyStreamProducesDurableFailureButHumanPauseDoesNot() {
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        var observed = new ArrayList<AgentRuntimeEvent>();
        try (TurnExecutionManager manager = new TurnExecutionManager(runtime)) {
            var received =
                    manager.start(
                                    request("turn-1", "session-1"),
                                    Flux.empty(),
                                    Duration.ofSeconds(1),
                                    observed::add)
                            .collectList()
                            .block(Duration.ofSeconds(3));
            assertEquals(
                    "MISSING_TERMINAL_RESULT",
                    ((Map<?, ?>) received.get(0).getDetails()).get("errorCode"));
            assertEquals(received, observed);
            var paused =
                    manager.start(
                                    request("turn-2", "session-1"),
                                    Flux.just(
                                            event(
                                                    AgentRuntimeEvent.Type.ASK_USER_REQUIRED,
                                                    "turn-2",
                                                    "session-1")),
                                    Duration.ofSeconds(1),
                                    null)
                            .collectList()
                            .block(Duration.ofSeconds(3));
            assertEquals(1, paused.size());
            assertEquals(AgentRuntimeEvent.Type.ASK_USER_REQUIRED, paused.get(0).getType());
        }
    }

    @Test
    void existingErrorIsNotRepeatedAndLateCompletionCannotOverwriteIt() {
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        try (TurnExecutionManager manager = new TurnExecutionManager(runtime)) {
            var received =
                    manager.start(
                                    request("turn-1", "session-1"),
                                    Flux.just(
                                                    event(
                                                            AgentRuntimeEvent.Type.TURN_FAILED,
                                                            "turn-1",
                                                            "session-1"),
                                                    event(
                                                            AgentRuntimeEvent.Type.TURN_COMPLETED,
                                                            "turn-1",
                                                            "session-1"))
                                            .concatWith(
                                                    Flux.error(
                                                            new IllegalStateException(
                                                                    "already reported"))),
                                    Duration.ofSeconds(1),
                                    null)
                            .collectList()
                            .block(Duration.ofSeconds(3));
            assertEquals(1, received.size());
            assertEquals(AgentRuntimeEvent.Type.TURN_FAILED, received.get(0).getType());
        }
    }

    @Test
    void failedCompletionDeliveryEmitsNoticeForClientStateReconciliation() {
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        try (TurnExecutionManager manager = new TurnExecutionManager(runtime)) {
            var received =
                    manager.start(
                                    request("turn-1", "session-1"),
                                    Flux.just(
                                            event(
                                                    AgentRuntimeEvent.Type.TURN_COMPLETED,
                                                    "turn-1",
                                                    "session-1")),
                                    Duration.ofSeconds(1),
                                    event -> {
                                        throw new IllegalStateException("delivery unavailable");
                                    })
                            .collectList()
                            .block(Duration.ofSeconds(3));
            assertEquals(AgentRuntimeEvent.Type.EXECUTION_NOTICE, received.get(0).getType());
            assertTrue(received.get(0).getText().contains("确认结果"));
        }
    }

    @Test
    void eventObserverNeverRunsOnReactorNonBlockingThread() {
        FakeRuntime runtime = new FakeRuntime("turn-1", "session-1");
        AtomicBoolean observerUsedNonBlockingThread = new AtomicBoolean();
        Flux<AgentRuntimeEvent> source =
                Flux.just(event(AgentRuntimeEvent.Type.TURN_STARTED, "turn-1", "session-1"))
                        .concatWith(
                                Mono.delay(Duration.ofMillis(10))
                                        .map(
                                                ignored ->
                                                        event(
                                                                AgentRuntimeEvent.Type
                                                                        .TURN_COMPLETED,
                                                                "turn-1",
                                                                "session-1")));

        try (TurnExecutionManager manager = new TurnExecutionManager(runtime, 16)) {
            List<AgentRuntimeEvent> events =
                    manager.start(
                                    request("turn-1", "session-1"),
                                    source,
                                    Duration.ofMinutes(1),
                                    ignored ->
                                            observerUsedNonBlockingThread.compareAndSet(
                                                    false, Schedulers.isInNonBlockingThread()))
                            .collectList()
                            .block(Duration.ofSeconds(1));

            assertFalse(observerUsedNonBlockingThread.get());
            assertEquals(
                    AgentRuntimeEvent.Type.TURN_COMPLETED, events.get(events.size() - 1).getType());
        }
    }

    private static AgentTurnRequest request(String turnId, String sessionId) {
        return AgentTurnRequest.builder()
                .turnId(turnId)
                .ownerKey("owner")
                .sessionId(sessionId)
                .message("hello")
                .build();
    }

    private static AgentRuntimeEvent event(
            AgentRuntimeEvent.Type type, String turnId, String sessionId) {
        return new AgentRuntimeEvent(
                type,
                turnId,
                sessionId,
                turnId,
                type.name(),
                null,
                type == AgentRuntimeEvent.Type.TURN_STARTED ? "running" : "success",
                null,
                null,
                null,
                null);
    }

    private static final class FakeRuntime implements AgentRuntime {
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicReference<SessionExecutionState> state;

        private FakeRuntime(String turnId, String sessionId) {
            this.state =
                    new AtomicReference<>(
                            SessionExecutionState.running(
                                    turnId, Instant.parse("2026-09-24T00:00:00Z")));
        }

        @Override
        public Flux<AgentRuntimeEvent> stream(AgentTurnRequest request) {
            return Flux.never();
        }

        @Override
        public Optional<SessionExecutionState> sessionExecution(String ownerKey, String sessionId) {
            return Optional.ofNullable(state.get());
        }

        @Override
        public boolean interruptCurrentTurn(
                String ownerKey, String sessionId, String expectedTurnId) {
            SessionExecutionState current = state.get();
            if (current == null
                    || !current.getTurnId().equals(expectedTurnId)
                    || current.getStatus() != TurnStatus.RUNNING) {
                return false;
            }
            interrupted.set(true);
            state.set(
                    current.finish(
                            TurnStatus.CANCELLED, Instant.parse("2026-09-24T00:00:01Z"), null));
            return true;
        }

        @Override
        public boolean failCurrentTurn(
                String ownerKey, String sessionId, String turnId, String code) {
            state.updateAndGet(current -> current.finish(TurnStatus.FAILED, Instant.now(), code));
            return true;
        }

        @Override
        public boolean timeoutCurrentTurn(String ownerKey, String sessionId) {
            SessionExecutionState current = state.get();
            timedOut.set(true);
            state.set(
                    current.finish(
                            TurnStatus.TIMED_OUT,
                            Instant.parse("2026-09-24T00:00:02Z"),
                            "HOST_TIMEOUT"));
            return true;
        }
    }
}
