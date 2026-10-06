package dev.horizen.agent.adapter.agentscope.runtime;

import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.runtime.api.SessionExecutionState;

import io.agentscope.core.state.State;

import java.beans.ConstructorProperties;
import java.time.Instant;

/**
 * AgentScope 存储标记不进入公开运行时契约，JSON 字段保持不变。
 */
public final class AgentScopeExecutionState extends SessionExecutionState implements State {
    /**
     * 创建Agent作用域执行工作状态，初始化该组件所需的状态、配置或依赖。
     *
     * @param turnId      单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param status      当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param startedAt   当前执行或执行段的开始时间。
     * @param finishedAt  执行结束时间；尚未结束的记录可以没有该时间。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     */
    @ConstructorProperties({"turnId", "status", "startedAt", "finishedAt", "failureCode"})
    public AgentScopeExecutionState(
            String turnId,
            TurnStatus status,
            Instant startedAt,
            Instant finishedAt,
            String failureCode) {
        super(turnId, status, startedAt, finishedAt, failureCode);
    }

    /**
     * 从输入构造Agent作用域执行工作状态。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的Agent作用域执行工作状态结果。
     */
    public static AgentScopeExecutionState from(SessionExecutionState value) {
        return new AgentScopeExecutionState(
                value.getTurnId(),
                value.getStatus(),
                value.getStartedAt(),
                value.getFinishedAt(),
                value.getFailureCode());
    }
}
