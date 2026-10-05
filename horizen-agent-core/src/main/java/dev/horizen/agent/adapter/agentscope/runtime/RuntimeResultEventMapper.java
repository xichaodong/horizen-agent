package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.List;
import java.util.Map;

/** RuntimeResultEventMapper 仅转换自身负责的原生事件类型。 */
final class RuntimeResultEventMapper {
    /**
     * 映射运行时结果事件映射器。
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

        if (source instanceof AgentResultEvent result && result.getResult() != null) {
            GenerateReason reason = stopReason(result.getResult());
            if (reason == GenerateReason.PERMISSION_ASKING
                    || reason == GenerateReason.TOOL_SUSPENDED
                    || reason == GenerateReason.MIDDLEWARE_STOP_REQUESTED) {
                return null;
            }
            String failureCode = stopFailureCode(reason);
            Map<String, Object> details =
                    Map.of(
                            "stopReason",
                            reason == null ? "UNKNOWN" : reason.name(),
                            "errorCode",
                            failureCode == null ? "" : failureCode);
            String text = result.getResult().getTextContent();
            if (failureCode != null) {
                String notice =
                        "MAX_ITERATIONS_REACHED".equals(failureCode)
                                ? "本轮已达到执行次数限制，尚未确认任务完成。"
                                : "AGENT_INTERRUPTED".equals(failureCode)
                                        ? "本轮执行已中断。"
                                        : "本轮执行异常结束，尚未确认任务完成。";
                text = notice + (text == null || text.isBlank() ? "" : "\n\n阶段结果：\n" + text);
            }
            if (isSubagent(source)) {
                return event(request, AgentRuntimeEvent.Type.SUBAGENT_RESULT, source.getId())
                        .text(text)
                        .status(failureCode == null ? "success" : "error")
                        .details(details)
                        .build();
            }
            long latency = elapsedMs(turnStartedAt);
            AgentRuntimeEvent.Type type =
                    failureCode == null
                            ? AgentRuntimeEvent.Type.TURN_COMPLETED
                            : reason == GenerateReason.INTERRUPTED
                                    ? AgentRuntimeEvent.Type.TURN_CANCELLED
                                    : AgentRuntimeEvent.Type.TURN_FAILED;
            return event(request, type, request.getTurnId())
                    .text(text)
                    .status(
                            failureCode == null
                                    ? "success"
                                    : reason == GenerateReason.INTERRUPTED ? "cancelled" : "failed")
                    .details(details)
                    .durationMs(latency)
                    .latencyMs(latency)
                    .build();
        }
        return null;
    }
}
