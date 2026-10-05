package dev.horizen.agent.execution.turn;

import dev.horizen.agent.common.validation.Preconditions;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;

/** 持久化且按所有者隔离的界面事件，用于在刷新后重建会话时间线。 */
@Getter
@EqualsAndHashCode
@ToString
public class TurnTimelineEvent {
    /** 当前记录在对应序列中的位置，用于排序或继续读取。 */
    private final long sequence;

    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private final String ownerKey;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private final String turnId;

    /** 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。 */
    private final String payloadJson;

    /** 当前记录的创建时间。 */
    private final Instant createdAt;

    /**
     * 创建执行时间线事件，初始化该组件所需的状态、配置或依赖。
     *
     * @param sequence 当前记录在对应序列中的位置，用于排序或继续读取。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param payloadJson 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     * @param createdAt 当前记录的创建时间。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
        "sequence",
        "ownerKey",
        "sessionId",
        "turnId",
        "payloadJson",
        "createdAt"
    })
    public TurnTimelineEvent(
            long sequence,
            String ownerKey,
            String sessionId,
            String turnId,
            String payloadJson,
            Instant createdAt) {
        if (sequence < 0) throw new IllegalArgumentException("sequence 不能为负数");
        this.sequence = sequence;
        this.ownerKey = Preconditions.requireText(ownerKey, "ownerKey 不能为空");
        this.sessionId = Preconditions.requireText(sessionId, "sessionId 不能为空");
        this.turnId = Preconditions.requireText(turnId, "turnId 不能为空");
        this.payloadJson = Preconditions.requireText(payloadJson, "payloadJson 不能为空");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
