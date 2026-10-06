package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.context.ContextCompactionTelemetry;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.List;
import java.util.Map;

/**
 * RuntimeNoticeEventMapper 仅转换自身负责的原生事件类型。
 */
final class RuntimeNoticeEventMapper {
    /**
     * 映射运行时Notice事件映射器。
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

        if (source instanceof CustomEvent custom
                && HarnessAgentEventMapper.NOTICE_EVENT_NAME.equals(custom.getName())) {
            String code =
                    custom.getValue() == null
                            ? "EXECUTION_ERROR"
                            : String.valueOf(custom.getValue().get("errorCode"));
            String text =
                    switch (code) {
                        case "PRESENTATION_FAILED" ->
                                "工具返回的展示内容出现异常，结果卡未能展示。原操作不会因此重复执行。";
                        case "MODEL_CONTEXT_OVERFLOW" -> "模型请求超出上下文限制，已整理上下文并重新尝试。";
                        case "TOOL_CATALOG_FAILED" -> "业务工具暂时无法加载，本轮无法使用这些工具。";
                        default -> "执行中出现异常，正在尝试继续处理。";
                    };
            return event(request, AgentRuntimeEvent.Type.EXECUTION_NOTICE, custom.getId())
                    .text(text)
                    .status("error")
                    .details(custom.getValue())
                    .build();
        }
        if (source instanceof CustomEvent custom
                && SubagentInteractionMiddleware.EVENT_NAME.equals(custom.getName())) {
            return event(request, AgentRuntimeEvent.Type.SUBAGENT_RESULT, custom.getId())
                    .text("该事项需要审批或澄清，子 Agent 未执行操作。")
                    .status("needs_parent")
                    .details(custom.getValue())
                    .build();
        }
        if (source instanceof CustomEvent custom
                && ContextCompactionTelemetry.EVENT_NAME.equals(custom.getName())) {
            return event(request, AgentRuntimeEvent.Type.CONTEXT_COMPACTED, custom.getId())
                    .status("success")
                    .details(custom.getValue())
                    .build();
        }
        if (source instanceof CustomEvent custom
                && ContextCompactionTelemetry.FAILURE_EVENT_NAME.equals(custom.getName())) {
            return event(request, AgentRuntimeEvent.Type.CONTEXT_COMPACTION_FAILED, custom.getId())
                    .text("上下文整理出现异常，正在尝试继续处理。")
                    .status("error")
                    .details(custom.getValue())
                    .build();
        }
        return null;
    }
}
