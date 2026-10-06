package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ModelEventMapper 仅转换自身负责的原生事件类型。
 */
final class ModelEventMapper {
    /**
     * 映射模型事件映射器。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param source  待解析或转换的来源对象。
     * @return 本次操作返回的Agent运行时事件结果。
     */
    static AgentRuntimeEvent map(RuntimeEventMappingContext context, AgentEvent source) {
        AgentTurnRequest request = context.getRequest();
        long turnStartedAt = context.getTurnStartedAt();
        Map<String, Long> stepStarts = context.getStepStarts();
        RuntimeContext runtimeContext = context.getRuntimeContext();
        List<AgentRuntimeEvent> notices = context.getNotices();
        HarnessAgent agent = context.getAgent();

        if (source instanceof ModelCallStartEvent start) {
            stepStarts.put(stepKey("model", source, start.getReplyId()), System.nanoTime());
            return event(request, AgentRuntimeEvent.Type.MODEL_STARTED, start.getReplyId())
                    .status("running")
                    .build();
        }
        if (source instanceof ThinkingBlockDeltaEvent delta && !delta.getDelta().isEmpty()) {
            return event(request, AgentRuntimeEvent.Type.THINKING_DELTA, delta.getReplyId())
                    .text(delta.getDelta())
                    .status("running")
                    .build();
        }
        if (source instanceof TextBlockDeltaEvent delta && !delta.getDelta().isEmpty()) {
            return event(request, AgentRuntimeEvent.Type.TEXT_DELTA, delta.getReplyId())
                    .text(delta.getDelta())
                    .status("running")
                    .build();
        }
        if (source instanceof ModelCallEndEvent end) {
            Map<String, Object> usage = null;
            if (end.getUsage() != null) {
                usage = new LinkedHashMap<>();
                usage.put("inputTokens", end.getUsage().getInputTokens());
                usage.put("outputTokens", end.getUsage().getOutputTokens());
                usage.put("totalTokens", end.getUsage().getTotalTokens());
            }
            return event(request, AgentRuntimeEvent.Type.MODEL_COMPLETED, end.getReplyId())
                    .status("success")
                    .details(usage)
                    .durationMs(
                            stepDuration(stepStarts, stepKey("model", source, end.getReplyId())))
                    .build();
        }
        return null;
    }
}
