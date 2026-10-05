package dev.horizen.agent.runtime.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.json.JsonUtils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class ValidatedRuntimeValuesTest {
    @Test
    void validatedRuntimeValuesExposeNoUncheckedSetters() {
        for (Class<?> type :
                List.of(
                        AgentContextBinding.class,
                        ToolApprovalDecision.class,
                        SessionExecutionState.class)) {
            assertTrue(
                    Arrays.stream(type.getMethods()).noneMatch(m -> m.getName().startsWith("set")),
                    type.getName());
        }
        var request =
                AgentTurnRequest.builder()
                        .ownerKey("owner")
                        .sessionId("session")
                        .turnId("turn")
                        .message("hello")
                        .context(String.class, "trusted")
                        .build();
        assertEquals("trusted", request.getContextBindings().get(0).getValue());
        assertThrows(
                UnsupportedOperationException.class, () -> request.getContextBindings().clear());
    }

    @Test
    void approvalDecisionRetainsWireFormatAndCopiesInputMap() throws Exception {
        Map<String, Object> input = new HashMap<>(Map.of("key", "initial"));
        var decision = new ToolApprovalDecision("call", "tool", "content", input, true);
        input.put("key", "changed");
        assertEquals("initial", decision.getInput().get("key"));
        assertThrows(
                UnsupportedOperationException.class, () -> decision.getInput().put("key", "bad"));
        assertEquals(
                decision, JsonUtils.read(JsonUtils.write(decision), ToolApprovalDecision.class));
        assertThrows(
                Exception.class,
                () ->
                        JsonUtils.read(
                                JsonUtils.write(decision)
                                        .replace("\"toolCallId\":\"call\"", "\"toolCallId\":\"\""),
                                ToolApprovalDecision.class));
    }

    @Test
    void sessionStateStillRoundTripsAndRejectsInvalidTerminalState() throws Exception {
        var state = SessionExecutionState.running("turn", Instant.parse("2026-10-05T00:00:00Z"));
        assertEquals(state, JsonUtils.read(JsonUtils.write(state), SessionExecutionState.class));
        assertThrows(
                Exception.class,
                () ->
                        JsonUtils.read(
                                JsonUtils.write(state).replace("RUNNING", "COMPLETED"),
                                SessionExecutionState.class));
    }
}
