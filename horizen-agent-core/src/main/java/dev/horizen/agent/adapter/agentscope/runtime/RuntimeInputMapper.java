package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行时输入映射边界：RuntimeInputMapper。
 */
final class RuntimeInputMapper {
    /**
     * 构造输入消息。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的Msg结果。
     */
    static Msg buildInputMessage(AgentTurnRequest request) {
        if (!request.getAskUserDecisions().isEmpty()) {
            return new ToolResultMessage(
                    request.getAskUserDecisions().stream()
                            .map(
                                    decision ->
                                            ToolResultBlock.text(decision.getAnswersJson())
                                                    .withIdAndName(
                                                            decision.getToolCallId(), "ask_user"))
                            .toList());
        }
        if (request.getApprovalDecisions().isEmpty()) {
            return new UserMessage(request.getMessage());
        }
        List<ConfirmResult> results =
                request.getApprovalDecisions().stream()
                        .map(
                                decision ->
                                        new ConfirmResult(
                                                decision.isApproved(),
                                                new ToolUseBlock(
                                                        decision.getToolCallId(),
                                                        decision.getToolName(),
                                                        decision.getInput(),
                                                        decision.getContent(),
                                                        Map.of())))
                        .toList();
        List<ContentBlock> content = new ArrayList<>();
        if (!request.getMessage().isBlank())
            content.add(TextBlock.builder().text(request.getMessage()).build());
        return UserMessage.builder()
                .content(content)
                .metadata(
                        results.isEmpty()
                                ? Map.of()
                                : Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }
}
