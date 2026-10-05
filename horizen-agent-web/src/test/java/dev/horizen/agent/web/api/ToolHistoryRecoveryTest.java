package dev.horizen.agent.web.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import org.junit.jupiter.api.Test;

import java.util.*;

class ToolHistoryRecoveryTest {
    private final AgentApiMapper mapper = new AgentApiMapper(false);

    @Test
    void completeSqlSnapshotCoversEarlierToolChunksButKeepsTextAndInFlightCalls() {
        var events =
                List.of(
                        event(1, "a", AgentRuntimeEvent.Type.TOOL_STARTED),
                        event(2, "a", AgentRuntimeEvent.Type.TOOL_INPUT_DELTA),
                        event(3, "a", AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA),
                        event(4, "a", AgentRuntimeEvent.Type.TOOL_COMPLETED),
                        event(5, "reply", AgentRuntimeEvent.Type.TEXT_DELTA),
                        event(6, "b", AgentRuntimeEvent.Type.TOOL_INPUT_DELTA));
        var snapshot =
                new RedisTurnEventBridge.EventSnapshot(events, 6, true, Map.of(1L, 10L, 4L, 11L));
        var end = mapper.streamEvent(events.get(3));
        end.setDetails(
                "{\"toolCallSnapshot\":{\"version\":1,\"input\":\"{}\",\"output\":\"done\"}}");
        var timeline =
                List.of(
                        new SessionApi.TimelineEventResponse(
                                10, "turn", mapper.streamEvent(events.get(0))),
                        new SessionApi.TimelineEventResponse(11, "turn", end));
        assertEquals(
                List.of(5L, 6L),
                ToolHistoryRecovery.uncovered(snapshot, timeline, mapper).stream()
                        .map(AgentRuntimeEvent::getStreamSequence)
                        .toList());
        assertEquals(6, snapshot.getLastSequence());
    }

    @Test
    void chunksAfterAnEarlierCompletionAndChildrenWithSameCallIdRemainRecoverable() {
        var done = event(1, "a", AgentRuntimeEvent.Type.TOOL_COMPLETED);
        var child = event(2, "a", AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA);
        child.setSource("session/worker");
        var next = event(3, "a", AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA);
        var persisted = mapper.streamEvent(done);
        persisted.setDetails("{\"toolCallSnapshot\":{\"version\":1,\"output\":\"old\"}}");
        var snapshot =
                new RedisTurnEventBridge.EventSnapshot(
                        List.of(done, child, next), 3, true, Map.of(1L, 11L));
        assertEquals(
                List.of(2L, 3L),
                ToolHistoryRecovery.uncovered(
                                snapshot,
                                List.of(
                                        new SessionApi.TimelineEventResponse(
                                                11, "turn", persisted)),
                                mapper)
                        .stream()
                        .map(AgentRuntimeEvent::getStreamSequence)
                        .toList());
    }

    @Test
    void legacyRowsWithoutCompleteSnapshotDoNotSuppressUncoveredChunks() {
        var chunk = event(1, "a", AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA);
        var end = event(2, "a", AgentRuntimeEvent.Type.TOOL_COMPLETED);
        var snapshot =
                new RedisTurnEventBridge.EventSnapshot(
                        List.of(chunk, end), 2, true, Map.of(2L, 11L));
        assertEquals(
                List.of(chunk),
                ToolHistoryRecovery.uncovered(
                        snapshot,
                        List.of(
                                new SessionApi.TimelineEventResponse(
                                        11, "turn", mapper.streamEvent(end))),
                        mapper));
    }

    private static AgentRuntimeEvent event(long sequence, String id, AgentRuntimeEvent.Type type) {
        var event =
                new AgentRuntimeEvent(
                        type,
                        "turn",
                        "session",
                        id,
                        "synthetic",
                        "text",
                        "success",
                        "synthetic-tool",
                        "chunk",
                        null,
                        null);
        if (type == AgentRuntimeEvent.Type.TOOL_COMPLETED) event.setDetails(null);
        event.setStreamSequence(sequence);
        return event;
    }
}
