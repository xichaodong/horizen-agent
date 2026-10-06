package dev.horizen.agent.application.interaction;

import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.identity.ExecutionIdentity;

/**
 * 用于恢复交互式 Turn 或将其标记为超时的输出接口。
 */
public interface AskUserTurnResumer {
    /**
     * 恢复提问用户执行恢复入口。
     *
     * @param identity    可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn        当前提问用户执行恢复入口持有的执行对象，供相应处理步骤使用。
     * @param request     当前操作的请求参数。
     * @param answersJson 回答集合的 JSON 表示，供持久化或协议转换使用。
     */
    void resume(
            ExecutionIdentity identity, AgentTurn turn, AskUserRequest request, String answersJson);

    /**
     * 完成当前操作的timeout步骤，按实现更新相应状态或依赖。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn     当前提问用户执行恢复入口持有的执行对象，供相应处理步骤使用。
     * @param request  当前操作的请求参数。
     */
    void timeout(ExecutionIdentity identity, AgentTurn turn, AskUserRequest request);
}
