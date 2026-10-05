package dev.horizen.agent.execution.turn;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.util.Objects;

/** Turn 状态迁移结果，用于区分幂等、并发变化和陈旧 Turn。 */
@Getter
@EqualsAndHashCode
@ToString
public class TransitionTurnResult {
    /** 本次领域操作的结果分类，区分已更新、重复与并发冲突等情形。 */
    private final Outcome outcome;

    /** 当前操作所关联的原执行事实。 */
    private final AgentTurn turn;

    /**
     * 创建状态转换执行结果，初始化该组件所需的状态、配置或依赖。
     *
     * @param outcome 当前状态转换执行结果持有的结果分类对象，供相应处理步骤使用。
     * @param turn 当前状态转换执行结果持有的执行对象，供相应处理步骤使用。
     */
    @ConstructorProperties({"outcome", "turn"})
    public TransitionTurnResult(Outcome outcome, AgentTurn turn) {
        outcome = Objects.requireNonNull(outcome, "outcome");

        this.outcome = outcome;
        this.turn = turn;
    }

    /** 执行状态转换的领域结果类别，标识是否成功更新或遭遇并发冲突。 */
    public enum Outcome {
        /** 本次更新已经提交。 */
        UPDATED,
        /** 执行已经处于所请求状态。 */
        ALREADY_IN_STATE,
        /** 在当前访问范围内没有找到所请求对象。 */
        NOT_FOUND,
        /** 执行状态已经被其他处理改变，需要使用最新状态判断。 */
        STATUS_CHANGED,
        /** 原会话的当前执行已经变化，旧请求不能作用于后续执行。 */
        TURN_CHANGED
    }
}
