package dev.horizen.agent.runtime.api;

import dev.horizen.agent.execution.turn.TurnStatus;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;

/**
 * Session 最新一轮执行的状态；历史过程由 Transcript 和 Trace 保存。
 */
@Getter
@EqualsAndHashCode
@ToString
public class SessionExecutionState {
    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private final String turnId;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private final TurnStatus status;

    /**
     * 当前执行或执行段的开始时间。
     */
    private final Instant startedAt;

    /**
     * 执行结束时间；尚未结束的记录可以没有该时间。
     */
    private final Instant finishedAt;

    /**
     * 机器可识别的失败分类，供状态恢复与错误展示使用。
     */
    private final String failureCode;

    /**
     * 创建会话执行工作状态，初始化该组件所需的状态、配置或依赖。
     *
     * @param turnId      单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param status      当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param startedAt   当前执行或执行段的开始时间。
     * @param finishedAt  执行结束时间；尚未结束的记录可以没有该时间。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({"turnId", "status", "startedAt", "finishedAt", "failureCode"})
    public SessionExecutionState(
            String turnId,
            TurnStatus status,
            Instant startedAt,
            Instant finishedAt,
            String failureCode) {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        status = Objects.requireNonNull(status, "status");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        if (!status.isTerminal() && finishedAt != null) {
            throw new IllegalArgumentException("running turn must not have finishedAt");
        }
        if (status.isTerminal() && finishedAt == null) {
            throw new IllegalArgumentException("terminal turn requires finishedAt");
        }

        this.turnId = turnId;
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.failureCode = failureCode;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param startedAt 当前执行或执行段的开始时间。
     * @return 本次操作返回的会话执行工作状态结果。
     */
    public static SessionExecutionState running(String turnId, Instant startedAt) {
        return new SessionExecutionState(turnId, TurnStatus.RUNNING, startedAt, null, null);
    }

    /**
     * 收敛会话执行工作状态。
     *
     * @param terminalStatus 当前会话执行工作状态持有的终态状态对象，供相应处理步骤使用。
     * @param finishedAt     执行结束时间；尚未结束的记录可以没有该时间。
     * @param failureCode    机器可识别的失败分类，供状态恢复与错误展示使用。
     * @return 本次操作返回的会话执行工作状态结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SessionExecutionState finish(
            TurnStatus terminalStatus, Instant finishedAt, String failureCode) {
        if (terminalStatus == null || !terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("terminalStatus must be terminal");
        }
        return new SessionExecutionState(
                turnId, terminalStatus, startedAt, finishedAt, failureCode);
    }
}
