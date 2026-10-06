package dev.horizen.agent.application.turn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class TurnRecoveryCoordinatorTest {
    private final ExecutionIdentity identity = new ExecutionIdentity("owner", "actor");
    private final SessionTurnStore sessions = mock(SessionTurnStore.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final TurnControlChannel controls = mock(TurnControlChannel.class);
    private final AgentTurn turn = mock(AgentTurn.class);

    @Test
    void routesCancellationToExecutorThroughApplicationPort() {
        when(turn.getSessionId()).thenReturn("session");
        when(turn.getOwnerKey()).thenReturn("owner");
        when(turn.getTurnId()).thenReturn("turn");
        when(turn.getStatus()).thenReturn(TurnStatus.RUNNING);
        when(turn.getExecutorId()).thenReturn("remote");
        when(sessions.findTurn("owner", "turn")).thenReturn(Optional.of(turn));
        when(sessions.transitionTurn(any()))
                .thenReturn(new TransitionTurnResult(TransitionTurnResult.Outcome.UPDATED, turn));
        try (var recovery = recovery()) {
            recovery.cancel(identity, "session", "turn");
            verify(controls)
                    .send(
                            "remote",
                            new TurnControlChannel.CancelSignal("owner", "session", "turn"));
            verifyNoInteractions(runtime);
        }
    }

    @Test
    void rejectsStaleSessionWithoutSendingCancellation() {
        when(turn.getSessionId()).thenReturn("other-session");
        when(sessions.findTurn("owner", "turn")).thenReturn(Optional.of(turn));
        try (var recovery = recovery()) {
            var error =
                    assertThrows(
                            ApplicationError.class,
                            () -> recovery.cancel(identity, "session", "turn"));
            assertEquals(ApplicationError.Code.CONFLICT, error.getCode());
            verify(sessions, never()).transitionTurn(any());
            verifyNoInteractions(controls, runtime);
        }
    }

    @Test
    void reportsUnacknowledgedRemoteCancellationAsUnavailable() {
        when(turn.getSessionId()).thenReturn("session");
        when(turn.getOwnerKey()).thenReturn("owner");
        when(turn.getTurnId()).thenReturn("turn");
        when(turn.getStatus()).thenReturn(TurnStatus.RUNNING);
        when(turn.getExecutorId()).thenReturn("remote");
        when(sessions.findTurn("owner", "turn")).thenReturn(Optional.of(turn));
        when(sessions.transitionTurn(any()))
                .thenReturn(new TransitionTurnResult(TransitionTurnResult.Outcome.UPDATED, turn));
        doThrow(new IllegalStateException("queue unavailable")).when(controls).send(any(), any());
        try (var recovery = recovery()) {
            var error =
                    assertThrows(
                            ApplicationError.class,
                            () -> recovery.cancel(identity, "session", "turn"));
            assertEquals(ApplicationError.Code.UNAVAILABLE, error.getCode());
            verifyNoInteractions(runtime);
        }
    }

    @Test
    void closePreventsBackgroundTasksFromRestarting() {
        var recovery = recovery();
        recovery.close();
        recovery.start();
        recovery.close();
        verifyNoInteractions(controls, sessions, runtime);
    }

    @Test
    void staleRecoveryDoesNotInterruptRuntimeOrPublishTerminalEvent() {
        stubTurn();
        when(turn.getVersion()).thenReturn(3L);
        when(sessions.transitionTurn(any()))
                .thenReturn(
                        new TransitionTurnResult(
                                TransitionTurnResult.Outcome.STATUS_CHANGED, turn));
        var persistence = mock(TurnEventPersistence.class);
        try (var recovery =
                     new TurnRecoveryCoordinator(
                             controls,
                             new TurnRecoveryPolicy(
                                     "local", Duration.ofSeconds(30), Duration.ofSeconds(30)),
                             sessions,
                             runtime,
                             new TurnExecutionManager(runtime),
                             null,
                             persistence)) {
            recovery.recover(turn, TurnStatus.FAILED, "EXECUTOR_LOST", Instant.now());
            var command = ArgumentCaptor.forClass(TransitionTurnCommand.class);
            verify(sessions).transitionTurn(command.capture());
            assertEquals(3L, command.getValue().getExpectedVersion());
            verifyNoInteractions(runtime, persistence);
        }
    }

    @Test
    void recoveryCommitsStateBeforeInterruptingAndPublishing() {
        stubTurn();
        when(sessions.transitionTurn(any()))
                .thenReturn(new TransitionTurnResult(TransitionTurnResult.Outcome.UPDATED, turn));
        var persistence = mock(TurnEventPersistence.class);
        try (var recovery =
                     new TurnRecoveryCoordinator(
                             controls,
                             new TurnRecoveryPolicy(
                                     "local", Duration.ofSeconds(30), Duration.ofSeconds(30)),
                             sessions,
                             runtime,
                             new TurnExecutionManager(runtime),
                             null,
                             persistence)) {
            recovery.recover(turn, TurnStatus.FAILED, "EXECUTOR_LOST", Instant.now());
            var order = inOrder(sessions, runtime, persistence);
            order.verify(sessions).transitionTurn(any());
            order.verify(runtime).failCurrentTurn("owner", "session", "turn", "EXECUTOR_LOST");
            order.verify(persistence).publish(eq("owner"), eq("session"), eq("turn"), any());
        }
    }

    @Test
    void failedControlDoesNotPreventOtherControlsOrFuturePolls() throws Exception {
        var goodHandled = new CountDownLatch(1);
        var polledAgain = new CountDownLatch(1);
        var reads = new AtomicInteger();
        when(runtime.sessionExecution(eq("owner"), eq("bad")))
                .thenThrow(new IllegalStateException("temporary read failure"));
        when(runtime.sessionExecution(eq("owner"), eq("good")))
                .thenAnswer(
                        i -> {
                            goodHandled.countDown();
                            return Optional.empty();
                        });
        when(controls.drain(anyString(), anyInt()))
                .thenAnswer(
                        i -> {
                            if (reads.incrementAndGet() == 1)
                                return Mono.just(
                                        List.of(
                                                new TurnControlChannel.CancelSignal(
                                                        "owner", "bad", "turn-bad"),
                                                new TurnControlChannel.CancelSignal(
                                                        "owner", "good", "turn-good")));
                            polledAgain.countDown();
                            return Mono.just(List.of());
                        });
        try (var recovery =
                     new TurnRecoveryCoordinator(
                             controls,
                             new TurnRecoveryPolicy("local", Duration.ofMillis(10), Duration.ofHours(1)),
                             sessions,
                             runtime,
                             new TurnExecutionManager(runtime),
                             null,
                             mock(TurnEventPersistence.class))) {
            recovery.start();
            assertTrue(goodHandled.await(2, TimeUnit.SECONDS));
            assertTrue(polledAgain.await(2, TimeUnit.SECONDS));
            verify(runtime, times(3)).sessionExecution("owner", "bad");
        }
    }

    private void stubTurn() {
        when(turn.getOwnerKey()).thenReturn("owner");
        when(turn.getSessionId()).thenReturn("session");
        when(turn.getTurnId()).thenReturn("turn");
    }

    private TurnRecoveryCoordinator recovery() {
        return new TurnRecoveryCoordinator(
                controls,
                new TurnRecoveryPolicy("local", Duration.ofSeconds(30), Duration.ofSeconds(30)),
                sessions,
                runtime,
                new TurnExecutionManager(runtime),
                null,
                mock(TurnEventPersistence.class));
    }
}
