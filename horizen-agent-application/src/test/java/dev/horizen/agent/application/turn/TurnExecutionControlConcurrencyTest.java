package dev.horizen.agent.application.turn;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.runtime.api.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class TurnExecutionControlConcurrencyTest {
    @Test
    void cancelInterruptsRuntimeWhilePersistenceIsBlockedAndPreservesOrder() throws Exception {
        var runtime = new FakeRuntime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observed = new CopyOnWriteArrayList<AgentRuntimeEvent.Type>();
        var activeObservers = new AtomicInteger();
        var cleaned = new AtomicBoolean();
        var producer = source();
        var callers = Executors.newSingleThreadExecutor();
        try (var manager = new TurnExecutionManager(runtime)) {
            var events =
                    manager.start(
                            request(),
                            producer.asFlux(),
                            Duration.ofMinutes(1),
                            event -> {
                                assertEquals(1, activeObservers.incrementAndGet());
                                try {
                                    if (event.getType() == AgentRuntimeEvent.Type.TEXT_DELTA) {
                                        entered.countDown();
                                        awaitUninterruptibly(release);
                                    }
                                    observed.add(event.getType());
                                } finally {
                                    activeObservers.decrementAndGet();
                                }
                            },
                            (event, error) -> {},
                            () -> {
                                assertEquals(0, activeObservers.get());
                                cleaned.set(true);
                            });
            producer.tryEmitNext(event(AgentRuntimeEvent.Type.TEXT_DELTA));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var result = callers.submit(() -> manager.cancel("owner", "session", "turn"));
            assertEquals(
                    TurnExecutionManager.CancelResult.CANCELLED, result.get(1, TimeUnit.SECONDS));
            assertEquals(1, runtime.interrupts.get());
            assertEquals(1, release.getCount(), "Cancel must not wait for persistence");
            assertFalse(cleaned.get(), "Source cancellation must not clear in-flight event facts");
            assertTrue(manager.hasActiveTurn("owner", "session"));
            assertThrows(
                    TurnExecutionManager.TurnAlreadyRunningException.class,
                    () -> manager.start(request(), Flux.never(), Duration.ofMinutes(1), null));
            producer.tryEmitNext(event(AgentRuntimeEvent.Type.TURN_COMPLETED));
            release.countDown();
            var received = events.collectList().block(Duration.ofSeconds(3));
            assertEquals(
                    List.of(
                            AgentRuntimeEvent.Type.TURN_STARTED,
                            AgentRuntimeEvent.Type.TEXT_DELTA,
                            AgentRuntimeEvent.Type.TURN_CANCELLED),
                    observed);
            assertEquals(observed, received.stream().map(AgentRuntimeEvent::getType).toList());
            assertFalse(manager.hasActiveTurn("owner", "session"));
            assertTrue(cleaned.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void deadlineStopsRuntimeBeforeBlockedPersistenceReturns() throws Exception {
        var runtime = new FakeRuntime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var producer = source();
        try (var manager = new TurnExecutionManager(runtime)) {
            var events =
                    manager.start(
                            request(),
                            producer.asFlux(),
                            Duration.ofMillis(300),
                            event -> {
                                if (event.getType() == AgentRuntimeEvent.Type.TEXT_DELTA) {
                                    entered.countDown();
                                    awaitUninterruptibly(release);
                                }
                            });
            producer.tryEmitNext(event(AgentRuntimeEvent.Type.TEXT_DELTA));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(
                    runtime.timedOut.await(2, TimeUnit.SECONDS),
                    "Deadline must not wait for observer");
            assertEquals(1, release.getCount());
            assertEquals(0, runtime.interrupts.get());
            release.countDown();
            var received = events.collectList().block(Duration.ofSeconds(3));
            assertEquals(
                    AgentRuntimeEvent.Type.TURN_TIMED_OUT,
                    received.get(received.size() - 1).getType());
            assertEquals(1, terminalCount(received));
        } finally {
            release.countDown();
        }
    }

    @Test
    void lateWriteFailureDoesNotReplaceAcceptedCancellation() throws Exception {
        var runtime = new FakeRuntime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failures = new AtomicInteger();
        var producer = source();
        try (var manager = new TurnExecutionManager(runtime)) {
            var events =
                    manager.start(
                            request(),
                            producer.asFlux(),
                            Duration.ofMinutes(1),
                            event -> {
                                if (event.getType() == AgentRuntimeEvent.Type.TEXT_DELTA) {
                                    entered.countDown();
                                    awaitUninterruptibly(release);
                                    throw new IllegalStateException("late synthetic write failure");
                                }
                            },
                            (event, error) -> failures.incrementAndGet());
            producer.tryEmitNext(event(AgentRuntimeEvent.Type.TEXT_DELTA));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(
                    TurnExecutionManager.CancelResult.CANCELLED,
                    manager.cancel("owner", "session", "turn"));
            release.countDown();
            var received = events.collectList().block(Duration.ofSeconds(3));
            assertEquals(
                    AgentRuntimeEvent.Type.TURN_CANCELLED,
                    received.get(received.size() - 1).getType());
            assertEquals(0, failures.get());
            assertEquals(0, runtime.failures.get());
            assertEquals(1, terminalCount(received));
        } finally {
            release.countDown();
        }
    }

    @Test
    void completionAlreadyWritingWinsOverCancellationWithoutBlockingControlCaller()
            throws Exception {
        var runtime = new FakeRuntime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var producer = source();
        var callers = Executors.newSingleThreadExecutor();
        try (var manager = new TurnExecutionManager(runtime)) {
            var events =
                    manager.start(
                            request(),
                            producer.asFlux(),
                            Duration.ofMinutes(1),
                            event -> {
                                if (event.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED) {
                                    entered.countDown();
                                    awaitUninterruptibly(release);
                                }
                            });
            producer.tryEmitNext(event(AgentRuntimeEvent.Type.TURN_COMPLETED));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(
                    TurnExecutionManager.CancelResult.ALREADY_TERMINAL,
                    callers.submit(() -> manager.cancel("owner", "session", "turn"))
                            .get(1, TimeUnit.SECONDS));
            assertEquals(0, runtime.interrupts.get());
            release.countDown();
            var received = events.collectList().block(Duration.ofSeconds(3));
            assertEquals(
                    AgentRuntimeEvent.Type.TURN_COMPLETED,
                    received.get(received.size() - 1).getType());
            assertEquals(1, terminalCount(received));
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void racingCompletionAndCancellationPersistExactlyOneTerminal() throws Exception {
        var callers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 20; iteration++) {
                var runtime = new FakeRuntime();
                var observed = new CopyOnWriteArrayList<AgentRuntimeEvent>();
                var producer = source();
                try (var manager = new TurnExecutionManager(runtime)) {
                    var events =
                            manager.start(
                                    request(),
                                    producer.asFlux(),
                                    Duration.ofMinutes(1),
                                    observed::add);
                    var barrier = new CyclicBarrier(2);
                    Future<?> completion =
                            callers.submit(
                                    () -> {
                                        awaitBarrier(barrier);
                                        producer.tryEmitNext(
                                                event(AgentRuntimeEvent.Type.TURN_COMPLETED));
                                    });
                    Future<TurnExecutionManager.CancelResult> cancellation =
                            callers.submit(
                                    () -> {
                                        awaitBarrier(barrier);
                                        return manager.cancel("owner", "session", "turn");
                                    });
                    completion.get(2, TimeUnit.SECONDS);
                    var result = cancellation.get(2, TimeUnit.SECONDS);
                    var received = events.collectList().block(Duration.ofSeconds(3));
                    assertEquals(1, terminalCount(received));
                    assertEquals(1, terminalCount(observed));
                    assertEquals(
                            result == TurnExecutionManager.CancelResult.CANCELLED
                                    ? AgentRuntimeEvent.Type.TURN_CANCELLED
                                    : AgentRuntimeEvent.Type.TURN_COMPLETED,
                            received.get(received.size() - 1).getType());
                }
            }
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void closeDoesNotWaitForObserverAndStopsSourceSubscription() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var producer = source();
        var manager = new TurnExecutionManager(new FakeRuntime());
        var callers = Executors.newSingleThreadExecutor();
        try {
            var events =
                    manager.start(
                            request(),
                            producer.asFlux().doOnCancel(() -> cancelled.set(true)),
                            Duration.ofMinutes(1),
                            event -> {
                                if (event.getType() == AgentRuntimeEvent.Type.TEXT_DELTA) {
                                    entered.countDown();
                                    awaitUninterruptibly(release);
                                }
                            });
            producer.tryEmitNext(event(AgentRuntimeEvent.Type.TEXT_DELTA));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            callers.submit(manager::close).get(1, TimeUnit.SECONDS);
            assertTrue(cancelled.get());
            release.countDown();
            events.collectList().block(Duration.ofSeconds(3));
            assertThrows(
                    TurnExecutionManager.ClosedException.class,
                    () -> manager.start(request(), Flux.never(), Duration.ofMinutes(1), null));
        } finally {
            release.countDown();
            manager.close();
            callers.shutdownNow();
        }
    }

    private static long terminalCount(List<AgentRuntimeEvent> events) {
        return events.stream()
                .filter(
                        event ->
                                switch (event.getType()) {
                                    case TURN_COMPLETED,
                                            TURN_FAILED,
                                            TURN_CANCELLED,
                                            TURN_TIMED_OUT ->
                                            true;
                                    default -> false;
                                })
                .count();
    }

    @Test
    void nonfatalObserverErrorStillTerminatesAndReleasesTheTurn() {
        var runtime = new FakeRuntime();
        try (var manager = new TurnExecutionManager(runtime)) {
            var events =
                    manager.start(
                            request(),
                            Flux.just(event(AgentRuntimeEvent.Type.TEXT_DELTA)),
                            Duration.ofSeconds(2),
                            event -> {
                                throw new AssertionError("synthetic callback failure");
                            });
            var received = events.collectList().block(Duration.ofSeconds(3));
            assertEquals(1, terminalCount(received));
            assertEquals(AgentRuntimeEvent.Type.TURN_FAILED, received.get(0).getType());
            assertFalse(manager.hasActiveTurn("owner", "session"));
        }
    }

    private static Sinks.Many<AgentRuntimeEvent> source() {
        Sinks.Many<AgentRuntimeEvent> source = Sinks.many().replay().all();
        source.tryEmitNext(event(AgentRuntimeEvent.Type.TURN_STARTED));
        return source;
    }

    private static AgentTurnRequest request() {
        return AgentTurnRequest.builder()
                .ownerKey("owner")
                .sessionId("session")
                .turnId("turn")
                .message("hello")
                .build();
    }

    private static AgentRuntimeEvent event(AgentRuntimeEvent.Type type) {
        return AgentRuntimeEvent.builder().type(type).turnId("turn").sessionId("session").build();
    }

    private static void awaitUninterruptibly(CountDownLatch release) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class FakeRuntime implements AgentRuntime {
        private final AtomicInteger interrupts = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final CountDownLatch timedOut = new CountDownLatch(1);

        @Override
        public Flux<AgentRuntimeEvent> stream(AgentTurnRequest request) {
            return Flux.never();
        }

        @Override
        public boolean interruptCurrentTurn(String owner, String session, String turn) {
            interrupts.incrementAndGet();
            return true;
        }

        @Override
        public boolean timeoutCurrentTurn(String owner, String session, String turn) {
            timedOut.countDown();
            return true;
        }

        @Override
        public boolean failCurrentTurn(String owner, String session, String turn, String code) {
            failures.incrementAndGet();
            return true;
        }
    }
}
