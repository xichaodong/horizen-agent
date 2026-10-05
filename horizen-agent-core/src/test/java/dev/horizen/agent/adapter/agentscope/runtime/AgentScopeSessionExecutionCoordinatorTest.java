package dev.horizen.agent.adapter.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.runtime.api.SessionExecutionState;

import io.agentscope.core.state.InMemoryAgentStateStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class AgentScopeSessionExecutionCoordinatorTest {
    @Test
    void usesAgentScopeCasToRejectOverlapAndStaleCompletion() {
        AgentScopeSessionExecutionCoordinator coordinator =
                new AgentScopeSessionExecutionCoordinator(new InMemoryAgentStateStore());
        Instant firstStarted = Instant.parse("2026-09-24T00:00:00Z");

        assertTrue(coordinator.tryStart("owner", "session", "turn-1", firstStarted).isPresent());
        assertTrue(coordinator.tryStart("owner", "session", "turn-2", firstStarted).isEmpty());
        assertTrue(
                coordinator.finish(
                        "owner",
                        "session",
                        "turn-1",
                        TurnStatus.COMPLETED,
                        firstStarted.plusSeconds(1),
                        null));
        assertTrue(
                coordinator
                        .tryStart("owner", "session", "turn-2", firstStarted.plusSeconds(2))
                        .isPresent());
        assertFalse(
                coordinator.finish(
                        "owner",
                        "session",
                        "turn-1",
                        TurnStatus.FAILED,
                        firstStarted.plusSeconds(3),
                        "LATE_FAILURE"));

        SessionExecutionState current = coordinator.get("owner", "session").orElseThrow();
        assertEquals("turn-2", current.getTurnId());
        assertEquals(TurnStatus.RUNNING, current.getStatus());
    }

    @Test
    void isolatesSameSessionIdAcrossAgentScopeUsers() {
        AgentScopeSessionExecutionCoordinator coordinator =
                new AgentScopeSessionExecutionCoordinator(new InMemoryAgentStateStore());
        Instant now = Instant.parse("2026-09-24T00:00:00Z");

        assertTrue(coordinator.tryStart("owner-a", "same-session", "turn-a", now).isPresent());
        assertTrue(coordinator.tryStart("owner-b", "same-session", "turn-b", now).isPresent());
    }

    @Test
    void hostTimeoutCanReclassifyAConcurrentCancellation() {
        AgentScopeSessionExecutionCoordinator coordinator =
                new AgentScopeSessionExecutionCoordinator(new InMemoryAgentStateStore());
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        coordinator.tryStart("owner", "session", "turn", now).orElseThrow();
        assertTrue(
                coordinator.finish(
                        "owner",
                        "session",
                        "turn",
                        TurnStatus.CANCELLED,
                        now.plusSeconds(1),
                        null));

        assertTrue(
                coordinator.timeoutCurrent("owner", "session", now.plusSeconds(2), "HOST_TIMEOUT"));
        SessionExecutionState state = coordinator.get("owner", "session").orElseThrow();
        assertEquals(TurnStatus.TIMED_OUT, state.getStatus());
        assertEquals("HOST_TIMEOUT", state.getFailureCode());
    }
}
