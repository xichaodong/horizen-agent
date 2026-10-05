package dev.horizen.agent.application.turn;

import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.ToolApprovalDecision;

import java.util.List;

/** 应用层执行请求的构造端口，由宿主补充身份、发布与输入资源。 */
@FunctionalInterface
public interface TurnRequestFactory {
    /**
     * 创建执行请求工厂。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param command 当前执行请求工厂持有的命令对象，供相应处理步骤使用。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param decisions 同一批待确认操作的决定集合，用于恢复原执行。
     * @return 本次操作返回的Agent执行请求结果。
     */
    AgentTurnRequest create(
            ExecutionIdentity identity,
            ChatCommand command,
            String turnId,
            List<ToolApprovalDecision> decisions);
}
