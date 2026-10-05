package dev.horizen.agent.application.turn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TurnTimelineEvent;
import dev.horizen.agent.execution.turn.TurnTimelineStore;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.ToolApprovalDecision;
import dev.horizen.agent.web.api.AgentApiMapper;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import io.agentscope.harness.agent.bus.MessageBus;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class ToolTimelinePersistenceTest {
    private final AgentApiMapper mapper = new AgentApiMapper(false);
    private final AgentTurnRequest turn =
            AgentTurnRequest.builder()
                    .ownerKey("synthetic-owner")
                    .sessionId("session")
                    .turnId("turn")
                    .message("synthetic task")
                    .build();

    @Test
    void hundredsOfChunksProduceTwoSqlFactsAndStillStreamEveryChunk() {
        MessageBus bus = mock(MessageBus.class);
        List<Map<String, Object>> streamed = new ArrayList<>();
        when(bus.logAppend(anyString(), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            streamed.add(call.getArgument(1));
                            return Mono.just(Integer.toString(streamed.size()));
                        });
        TurnTimelineStore timeline = mock(TurnTimelineStore.class);
        List<ChatApi.ChatStreamEvent> stored = new ArrayList<>();
        AtomicLong sequence = new AtomicLong();
        when(timeline.append(anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            String payload = call.getArgument(3);
                            stored.add(mapper.timelineEvent(payload));
                            return new TurnTimelineEvent(
                                    sequence.incrementAndGet(),
                                    call.getArgument(0),
                                    call.getArgument(1),
                                    call.getArgument(2),
                                    payload,
                                    call.getArgument(4));
                        });
        var persistence =
                new TurnEventPersistence(
                        mock(SessionTurnStore.class),
                        mock(ApprovalStore.class),
                        null,
                        timeline,
                        new RedisTurnEventBridge(bus),
                        mock(AgentRuntime.class),
                        event -> mapper.json(mapper.streamEvent(event)),
                        t -> {},
                        t -> {});
        var projection = new ToolTimelineProjection(turn);
        var identity = new ExecutionIdentity("synthetic-owner", "synthetic-actor");
        persistence.observe(
                identity,
                turn,
                event(AgentRuntimeEvent.Type.TOOL_STARTED, "call", null),
                projection);
        String input = "{\"text\":\"" + "x".repeat(713) + "\"}";
        for (char value : input.toCharArray()) {
            persistence.observe(
                    identity,
                    turn,
                    event(
                            AgentRuntimeEvent.Type.TOOL_INPUT_DELTA,
                            "call",
                            Character.toString(value)),
                    projection);
        }
        // 此完整输出超过单个 SSE 事件限制。小片段通过流传输，只有 REST/MySQL
        // 持久化事实携带组装后的完整结果。
        String output = "result-".repeat(60_000);
        for (int offset = 0; offset < output.length(); offset += 1000) {
            persistence.observe(
                    identity,
                    turn,
                    event(
                            AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA,
                            "call",
                            output.substring(offset, Math.min(output.length(), offset + 1000))),
                    projection);
        }
        persistence.observe(
                identity,
                turn,
                event(AgentRuntimeEvent.Type.TOOL_COMPLETED, "call", null),
                projection);
        assertEquals(2, stored.size());
        assertEquals(
                List.of("tool_start", "tool_end"),
                stored.stream().map(ChatApi.ChatStreamEvent::getType).toList());
        Map<?, ?> snapshot =
                (Map<?, ?>) mapper.jsonMap(stored.get(1).getDetails()).get("toolCallSnapshot");
        assertEquals(input, snapshot.get("input"));
        assertEquals(output, snapshot.get("output"));
        assertEquals(0, projection.getBytes());
        assertEquals(0, projection.activeCalls());
        assertEquals(input.length() + 420 + 2, streamed.size());
        assertNull(streamed.get(streamed.size() - 1).get("details"));
        assertTrue(streamed.stream().anyMatch(e -> "TOOL_INPUT_DELTA".equals(e.get("type"))));
        assertTrue(streamed.stream().anyMatch(e -> "TOOL_OUTPUT_DELTA".equals(e.get("type"))));
    }

    @Test
    void approvalResumeRestoresFrozenInputWithoutNeedingOldChunks() {
        var resumed =
                AgentTurnRequest.builder()
                        .ownerKey("synthetic-owner")
                        .sessionId("session")
                        .turnId("turn")
                        .message("resume")
                        .approvalDecisions(
                                List.of(
                                        new ToolApprovalDecision(
                                                "call",
                                                "synthetic-tool",
                                                "",
                                                Map.of("value", 7),
                                                true)))
                        .build();
        var projection = new ToolTimelineProjection(resumed);
        projection.accept(event(AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA, "call", "saved"));
        var complete =
                projection.complete(event(AgentRuntimeEvent.Type.TOOL_COMPLETED, "call", null));
        var snapshot = snapshot(complete);
        assertEquals("{\"value\":7}", snapshot.get("input"));
        assertEquals("saved", snapshot.get("output"));
    }

    @Test
    void parentAndChildCallsWithSameIdHaveSeparateBuffersAndProvenance() {
        var projection = new ToolTimelineProjection(turn);
        projection.accept(event(AgentRuntimeEvent.Type.TOOL_INPUT_DELTA, "call", "parent"));
        var child = event(AgentRuntimeEvent.Type.TOOL_INPUT_DELTA, "call", "child");
        child.setSource("session/worker");
        child.setTaskId("task-1");
        projection.accept(child);
        var childEnd = event(AgentRuntimeEvent.Type.TOOL_COMPLETED, "call", null);
        childEnd.setSource(child.getSource());
        childEnd.setTaskId(child.getTaskId());
        childEnd.setDepth(1);
        var finished = projection.complete(childEnd);
        assertEquals("child", snapshot(finished).get("input"));
        assertEquals("session/worker", finished.getSource());
        assertEquals(1, finished.getDepth());
        assertEquals(
                "parent",
                snapshot(
                                projection.complete(
                                        event(AgentRuntimeEvent.Type.TOOL_COMPLETED, "call", null)))
                        .get("input"));
        assertEquals(0, projection.getBytes());
    }

    @Test
    void bufferLimitsRejectOverflowBeforeAppendingAndCleanupReleasesPartialCalls() {
        var projection = new ToolTimelineProjection(turn, 4, 6, 8);
        projection.accept(event(AgentRuntimeEvent.Type.TOOL_INPUT_DELTA, "call", "ab"));
        projection.accept(event(AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA, "call", "中文"));
        assertEquals(8, projection.getBytes());
        assertThrows(
                ToolTimelineProjection.SnapshotLimitException.class,
                () ->
                        projection.accept(
                                event(AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA, "call", "x")));
        assertEquals(8, projection.getBytes());
        assertThrows(
                ToolTimelineProjection.SnapshotLimitException.class,
                () ->
                        projection.accept(
                                event(AgentRuntimeEvent.Type.TOOL_INPUT_DELTA, "other", "x")));
        projection.clear();
        assertEquals(0, projection.getBytes());
        assertEquals(0, projection.activeCalls());
    }

    @Test
    void unavailableResumedInputDoesNotOverwriteAnEarlierSnapshot() {
        var projection = new ToolTimelineProjection(turn);
        assertFalse(
                snapshot(
                                projection.complete(
                                        event(AgentRuntimeEvent.Type.TOOL_COMPLETED, "call", null)))
                        .containsKey("input"));
    }

    private static Map<?, ?> snapshot(AgentRuntimeEvent event) {
        return (Map<?, ?>) ((Map<?, ?>) event.getDetails()).get("toolCallSnapshot");
    }

    private static AgentRuntimeEvent event(AgentRuntimeEvent.Type type, String id, Object details) {
        return new AgentRuntimeEvent(
                type,
                "turn",
                "session",
                id,
                "synthetic tool",
                null,
                type == AgentRuntimeEvent.Type.TOOL_COMPLETED ? "success" : "running",
                "synthetic-tool",
                details,
                null,
                null);
    }
}
