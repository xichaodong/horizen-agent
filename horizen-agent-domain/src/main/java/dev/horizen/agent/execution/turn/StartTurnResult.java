package dev.horizen.agent.execution.turn;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.util.Objects;

/** Turn 启动的原子结果。 */
@Getter
@EqualsAndHashCode
@ToString
public class StartTurnResult {
    /** 本次领域操作的结果分类，区分已更新、重复与并发冲突等情形。 */
    private final Outcome outcome;

    /** 当前操作所关联的原执行事实。 */
    private final AgentTurn turn;

    /** 会话当前占用的执行标识；无活跃执行时为空。 */
    private final String activeTurnId;

    /**
     * 创建启动执行结果，初始化该组件所需的状态、配置或依赖。
     *
     * @param outcome 当前启动执行结果持有的结果分类对象，供相应处理步骤使用。
     * @param turn 当前启动执行结果持有的执行对象，供相应处理步骤使用。
     * @param activeTurnId 会话当前占用的执行标识；无活跃执行时为空。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({"outcome", "turn", "activeTurnId"})
    public StartTurnResult(Outcome outcome, AgentTurn turn, String activeTurnId) {
        outcome = Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.STARTED || outcome == Outcome.DUPLICATE) && turn == null) {
            throw new IllegalArgumentException("启动或幂等命中时必须返回 Turn");
        }

        this.outcome = outcome;
        this.turn = turn;
        this.activeTurnId = activeTurnId;
    }

    /** 启动执行的领域结果类别，区分已启动、重复请求与会话占用等状态。 */
    public enum Outcome {
        /** 新的执行已经登记。 */
        STARTED,
        /** 请求标识重复，复用或回放原执行结果。 */
        DUPLICATE,
        /** 同一隔离会话已有未结束的执行。 */
        SESSION_BUSY,
        /** 原会话已归档，不能启动新执行。 */
        SESSION_ARCHIVED
    }
}
