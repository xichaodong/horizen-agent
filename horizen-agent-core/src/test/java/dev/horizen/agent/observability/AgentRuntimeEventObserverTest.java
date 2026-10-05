package dev.horizen.agent.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

class AgentRuntimeEventObserverTest {
    @Test
    void omitsTextAndToolPayloadUnlessContentCaptureIsEnabled() {
        var traces = new ArrayList<TraceEvent>();
        var observer = new AgentRuntimeEventObserver(traces::add);

        observer.accept(
                new AgentRuntimeEvent(
                        AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA,
                        "turn-1",
                        "session-1",
                        "tool-1",
                        "工具结果",
                        "secret text",
                        "running",
                        "demo",
                        "secret payload",
                        null,
                        null));
        observer.accept(
                new AgentRuntimeEvent(
                        AgentRuntimeEvent.Type.MODEL_COMPLETED,
                        "turn-1",
                        "session-1",
                        "model-1",
                        "模型调用",
                        null,
                        "success",
                        null,
                        Map.of("totalTokens", 12),
                        4L,
                        null));

        assertFalse(traces.get(0).getAttributes().toString().contains("secret"));
        assertTrue(traces.get(1).getAttributes().toString().contains("totalTokens"));
    }
}
