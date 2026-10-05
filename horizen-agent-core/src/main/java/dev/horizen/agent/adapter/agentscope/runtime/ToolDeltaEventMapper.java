package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.List;
import java.util.Map;

/** ToolDeltaEventMapper 仅转换自身负责的原生事件类型。 */
final class ToolDeltaEventMapper {
    /**
     * 映射工具增量事件映射器。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param source 待解析或转换的来源对象。
     * @return 本次操作返回的Agent运行时事件结果。
     */
    static AgentRuntimeEvent map(RuntimeEventMappingContext context, AgentEvent source) {
        AgentTurnRequest request = context.getRequest();
        long turnStartedAt = context.getTurnStartedAt();
        Map<String, Long> stepStarts = context.getStepStarts();
        RuntimeContext runtimeContext = context.getRuntimeContext();
        List<AgentRuntimeEvent> notices = context.getNotices();
        HarnessAgent agent = context.getAgent();

        if (source instanceof ToolCallStartEvent start) {
            stepStarts.put(stepKey("tool", source, start.getToolCallId()), System.nanoTime());
            return event(request, AgentRuntimeEvent.Type.TOOL_STARTED, start.getToolCallId())
                    .status("running")
                    .toolName(start.getToolCallName())
                    .build();
        }
        if (source instanceof ToolCallDeltaEvent delta && !delta.getDelta().isEmpty()) {
            return event(request, AgentRuntimeEvent.Type.TOOL_INPUT_DELTA, delta.getToolCallId())
                    .status("running")
                    .toolName(delta.getToolCallName())
                    .details(delta.getDelta())
                    .build();
        }
        if (source instanceof ToolResultTextDeltaEvent delta && !delta.getDelta().isEmpty()) {
            return event(request, AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA, delta.getToolCallId())
                    .status("running")
                    .toolName(delta.getToolCallName())
                    .details(delta.getDelta())
                    .build();
        }
        if (source instanceof ToolResultDataDeltaEvent delta) {
            return event(request, AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA, delta.getToolCallId())
                    .status("running")
                    .toolName(delta.getToolCallName())
                    .details(delta.getData())
                    .build();
        }
        return null;
    }
}
