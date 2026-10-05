package dev.horizen.agent.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

class EvaluationEvidenceTest {
    @Test
    void clarificationEvidenceAcceptsDomainTimestamps() {
        var status = new EvaluationProtocol.Status();
        var evidence = new EvaluationEvidence(status);
        evidence.accept(
                event(
                        AgentRuntimeEvent.Type.ASK_USER_REQUIRED,
                        Map.of("createdAt", Instant.parse("2026-10-03T00:00:00Z"))));
        assertEquals(1, status.getEvents().size());
        assertEquals("ASK_USER_REQUIRED", status.getEvents().get(0).getEventName());
    }

    private AgentRuntimeEvent event(AgentRuntimeEvent.Type type, Object details) {
        return new AgentRuntimeEvent(
                type, "turn", "session", "call", "title", null, "success", "query", details, 1L,
                1L);
    }

    @Test
    void completeToolArgumentsAndUsageUseServerEvaluationSchema() {
        var status = new EvaluationProtocol.Status();
        var evidence = new EvaluationEvidence(status);
        evidence.accept(event(AgentRuntimeEvent.Type.TOOL_STARTED, null));
        evidence.accept(event(AgentRuntimeEvent.Type.TOOL_INPUT_DELTA, "{\"id\":"));
        evidence.accept(event(AgentRuntimeEvent.Type.TOOL_INPUT_DELTA, "42}"));
        evidence.accept(event(AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA, "{\"success\":true}"));
        evidence.accept(event(AgentRuntimeEvent.Type.TOOL_COMPLETED, null));
        evidence.accept(
                event(
                        AgentRuntimeEvent.Type.MODEL_COMPLETED,
                        Map.of("inputTokens", 10, "outputTokens", 3)));
        assertEquals(
                Map.of("id", 42), ((Map<?, ?>) status.getEvents().get(0).getPayload()).get("args"));
        assertEquals(
                Map.of("success", true),
                ((Map<?, ?>) status.getEvents().get(1).getPayload()).get("result"));
        assertEquals(
                Map.of("promptTokens", 10, "completionTokens", 3),
                ((Map<?, ?>) status.getEvents().get(2).getPayload()).get("usage"));
        evidence.seal();
        assertEquals(1L, ((Map<?, ?>) status.getActualOutput().get("counters")).get("toolCalls"));
        assertEquals(
                1L, ((Map<?, ?>) status.getActualOutput().get("counters")).get("successCount"));
    }
}
