package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SubagentEventMapper 仅转换自身负责的原生事件类型。 */
final class SubagentEventMapper {
    /**
     * 映射子Agent事件映射器。
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

        if (isSubagent(source) && source instanceof AgentStartEvent start) {
            stepStarts.put(stepKey("agent", source, start.getReplyId()), System.nanoTime());
            Map<String, Object> details = new LinkedHashMap<>();
            if (start.getSessionId() != null) details.put("agentSessionId", start.getSessionId());
            if (start.getRole() != null) details.put("role", start.getRole());
            return event(request, AgentRuntimeEvent.Type.SUBAGENT_STARTED, start.getReplyId())
                    .status("running")
                    .details(details)
                    .build();
        }
        if (isSubagent(source) && source instanceof AgentEndEvent end) {
            return event(request, AgentRuntimeEvent.Type.SUBAGENT_COMPLETED, end.getReplyId())
                    .status("ended")
                    .durationMs(
                            stepDuration(stepStarts, stepKey("agent", source, end.getReplyId())))
                    .build();
        }
        return null;
    }
}
