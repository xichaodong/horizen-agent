package dev.horizen.agent.application.session;

import dev.horizen.agent.execution.turn.TurnStatus;

import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/** Session 最新 Turn 的视图，不依赖传输协议。 */
@Getter
public final class SessionExecutionView {
    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private final String turnId;

    /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
    private final TurnStatus status;

    /** 当前执行或执行段的开始时间。 */
    private final Instant startedAt;

    /** 执行结束时间；尚未结束的记录可以没有该时间。 */
    private final Instant finishedAt;

    /** 机器可识别的失败分类，供状态恢复与错误展示使用。 */
    private final String failureCode;

    /**
     * 创建会话执行视图，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param startedAt 当前执行或执行段的开始时间。
     * @param finishedAt 执行结束时间；尚未结束的记录可以没有该时间。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     */
    public SessionExecutionView(
            String sessionId,
            String turnId,
            TurnStatus status,
            Instant startedAt,
            Instant finishedAt,
            String failureCode) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.turnId = turnId;
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.failureCode = failureCode;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的会话执行视图结果。
     */
    public static SessionExecutionView idle(String sessionId) {
        return new SessionExecutionView(sessionId, null, null, null, null, null);
    }

    /**
     * 检查idle对应的条件，供调用方选择后续处理分支。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean idle() {
        return turnId == null;
    }
}
