package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.ApprovalPresentation;
import dev.horizen.agent.runtime.api.ToolApprovalRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.List;
import java.util.Map;

/** InteractionEventMapper 仅转换自身负责的原生事件类型。 */
final class InteractionEventMapper {
    /**
     * 映射交互事件映射器。
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

        if (source instanceof RequireUserConfirmEvent confirm) {
            List<ToolApprovalRequest> requests =
                    confirm.getToolCalls().stream()
                            .map(
                                    tool -> {
                                        ToolApprovalRequest pending =
                                                new ToolApprovalRequest(
                                                        confirm.getReplyId(),
                                                        tool.getId(),
                                                        tool.getName(),
                                                        tool.getContent(),
                                                        tool.getInput());
                                        ApprovalPresentation presentation =
                                                ApprovalPresentation.fallback(tool.getName());
                                        var registered = agent.getToolkit().getTool(tool.getName());
                                        if (registered
                                                instanceof ApprovalPresentationProvider provider) {
                                            try {
                                                var supplied =
                                                        provider.describeApproval(
                                                                pending.getInput(), runtimeContext);
                                                if (supplied != null) presentation = supplied;
                                            } catch (RuntimeException ignored) {
                                                // 展示失败不能导致待审批操作被执行或丢失。
                                                notices.add(
                                                        event(
                                                                        request,
                                                                        AgentRuntimeEvent.Type
                                                                                .EXECUTION_NOTICE,
                                                                        "approval-display-"
                                                                                + tool.getId())
                                                                .text(
                                                                        "审批详情展示出现异常，已使用基础信息展示，操作仍需确认。")
                                                                .status("error")
                                                                .toolName(tool.getName())
                                                                .details(
                                                                        Map.of(
                                                                                "errorCode",
                                                                                "APPROVAL_PRESENTATION_FAILED"))
                                                                .build());
                                            }
                                        }
                                        pending.setPresentation(presentation);
                                        return pending;
                                    })
                            .toList();
            return event(request, AgentRuntimeEvent.Type.APPROVAL_REQUIRED, confirm.getReplyId())
                    .status("waiting_approval")
                    .details(requests)
                    .build();
        }
        if (source instanceof UserConfirmResultEvent confirmed) {
            return event(request, AgentRuntimeEvent.Type.APPROVAL_RESOLVED, confirmed.getReplyId())
                    .status("running")
                    .details(
                            confirmed.getConfirmResults().stream()
                                    .map(
                                            result ->
                                                    Map.of(
                                                            "toolCallId",
                                                                    result.getToolCall().getId(),
                                                            "approved", result.isConfirmed()))
                                    .toList())
                    .build();
        }
        return null;
    }
}
