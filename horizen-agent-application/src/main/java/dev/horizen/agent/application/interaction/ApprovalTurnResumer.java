package dev.horizen.agent.application.interaction;

import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.identity.ExecutionIdentity;

import java.time.Duration;
import java.util.List;

/** 审批编排用于继续 Agent 运行时的输出接口。 */
@FunctionalInterface
public interface ApprovalTurnResumer {
    /**
     * 恢复审批执行恢复入口。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前审批执行恢复入口持有的执行对象，供相应处理步骤使用。
     * @param resolutions 决定结果集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param remaining 剩余的时间配置，供等待、调度或失效判断使用。
     */
    void resume(
            ExecutionIdentity identity,
            AgentTurn turn,
            List<ApprovalResolution> resolutions,
            Duration remaining);
}
