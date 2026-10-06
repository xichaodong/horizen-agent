package dev.horizen.agent.domain.askuser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 按归属范围保存澄清请求、回答与恢复状态的持久化端口。
 */
public interface AskUserStore {
    /**
     * 创建候选查找。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的提问用户请求结果。
     */
    AskUserRequest createOrFind(AskUserRequest request);

    /**
     * 查找提问用户存储。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param askUserId 待回答澄清请求的标识，恢复时与原问题记录关联。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<AskUserRequest> find(String ownerKey, String askUserId);

    /**
     * 查找待处理。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    List<AskUserRequest> findPending(String ownerKey, String sessionId, String turnId);

    /**
     * 解析提问用户存储。
     *
     * @param ownerKey        宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param askUserId       待回答澄清请求的标识，恢复时与原问题记录关联。
     * @param status          当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param answersJson     回答集合的 JSON 表示，供持久化或协议转换使用。
     * @param resolvedAt      已解析的时间，用于记录对应生命周期节点。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @return 本次操作返回的提问用户请求结果。
     */
    AskUserRequest resolve(
            String ownerKey,
            String askUserId,
            AskUserStatus status,
            String answersJson,
            Instant resolvedAt,
            long expectedVersion);
}
