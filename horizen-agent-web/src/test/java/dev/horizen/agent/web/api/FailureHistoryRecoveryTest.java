package dev.horizen.agent.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.execution.turn.TurnTimelineEvent;
import dev.horizen.agent.execution.turn.TurnTimelineStore;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class FailureHistoryRecoveryTest {
    @Test
    void failedTurnWithoutTimelineRestoresVisibleErrorAndDoesNotAdvanceCursor() {
        for (TurnStatus status :
                List.of(TurnStatus.FAILED, TurnStatus.TIMED_OUT, TurnStatus.CANCELLED)) {
            var sessions = mock(SessionTurnStore.class);
            var turn = mock(AgentTurn.class);
            when(turn.getStatus()).thenReturn(status);
            when(turn.getTurnId()).thenReturn("turn");
            when(turn.getFailureCode()).thenReturn("UNKNOWN_FAILURE");
            when(sessions.findLatestTurn("owner", "session")).thenReturn(Optional.of(turn));
            var api =
                    new AgentSessionApiService(
                            mock(AgentRuntime.class),
                            sessions,
                            null,
                            null,
                            null,
                            null,
                            new AgentApiMapper(false));
            var history = api.messages(new ExecutionIdentity("owner", "actor"), "session");
            assertEquals(1, history.getTimelineEvents().size());
            assertEquals(0, history.getTimelineEvents().get(0).getSequence());
            var event = history.getTimelineEvents().get(0).getEvent();
            assertEquals(status == TurnStatus.CANCELLED ? "cancelled" : "error", event.getType());
            assertNotNull(event.getText());
            assertFalse(event.getText().isBlank());
            verify(sessions).findLatestTurn("owner", "session");
        }
    }

    @Test
    void existingPartialFailureIsRetainedWithoutDuplicateFallback() {
        var sessions = mock(SessionTurnStore.class);
        var turn = mock(AgentTurn.class);
        when(turn.getStatus()).thenReturn(TurnStatus.FAILED);
        when(turn.getTurnId()).thenReturn("turn");
        when(sessions.findLatestTurn("owner", "session")).thenReturn(Optional.of(turn));
        var timeline = mock(TurnTimelineStore.class);
        var mapper = new AgentApiMapper(false);
        var failed =
                new AgentRuntimeEvent(
                        AgentRuntimeEvent.Type.TURN_FAILED,
                        "turn",
                        "session",
                        "turn",
                        "执行异常",
                        "synthetic partial summary",
                        "failed",
                        null,
                        Map.of("errorCode", "MAX_ITERATIONS_REACHED"),
                        null,
                        null);
        when(timeline.listForSession("owner", "session"))
                .thenReturn(
                        List.of(
                                new TurnTimelineEvent(
                                        7L,
                                        "owner",
                                        "session",
                                        "turn",
                                        mapper.json(mapper.streamEvent(failed)),
                                        Instant.now())));
        var api =
                new AgentSessionApiService(
                        mock(AgentRuntime.class), sessions, null, timeline, null, null, mapper);
        var history = api.messages(new ExecutionIdentity("owner", "actor"), "session");
        assertEquals(1, history.getTimelineEvents().size());
        assertEquals(
                "synthetic partial summary",
                history.getTimelineEvents().get(0).getEvent().getText());
        assertEquals(7L, history.getTimelineEvents().get(0).getSequence());
    }

    @Test
    void unknownErrorAndTimeoutReceiveNonEmptyGenericText() {
        var mapper = new AgentApiMapper(false);
        for (var type :
                List.of(
                        AgentRuntimeEvent.Type.TURN_FAILED,
                        AgentRuntimeEvent.Type.TURN_TIMED_OUT)) {
            var mapped =
                    mapper.streamEvent(
                            new AgentRuntimeEvent(
                                    type,
                                    "turn",
                                    "session",
                                    "turn",
                                    null,
                                    null,
                                    "failed",
                                    null,
                                    Map.of("errorCode", "FUTURE_FAILURE"),
                                    null,
                                    null));
            assertEquals("error", mapped.getType());
            assertFalse(mapped.getText().isBlank());
        }
    }

    @Test
    void streamRecoveryFailureIsVisibleButDoesNotClaimTheTaskFailed() {
        var event = new AgentApiMapper(false).sessionError("synthetic stream recovery failure");
        assertEquals("execution_notice", event.getType());
        assertEquals("unknown", event.getStatus());
        assertFalse(event.getText().isBlank());
    }
}
