package dev.horizen.agent.execution.turn;

import java.time.Instant;
import java.util.List;

/**
 * 仅追加的持久化时间线，保存用户可见的 Turn 事件。
 */
public interface TurnTimelineStore {
    /**
     * 追加执行时间线存储。
     *
     * @param ownerKey    宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId   会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId      单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param payloadJson 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     * @param createdAt   当前记录的创建时间。
     * @return 本次操作返回的执行时间线事件结果。
     */
    TurnTimelineEvent append(
            String ownerKey,
            String sessionId,
            String turnId,
            String payloadJson,
            Instant createdAt);

    /**
     * 查询列表中的目标范围会话。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<TurnTimelineEvent> listForSession(String ownerKey, String sessionId);
}
