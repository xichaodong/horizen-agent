package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.ConversationHistoryRow;
import dev.horizen.agent.storage.jdbc.model.SessionRow;
import dev.horizen.agent.storage.jdbc.model.TurnRow;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** JdbcSessionTurnStore 的数据库操作。 */
public interface SessionTurnMapper {
    /**
     * 写入新的执行记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param requestId 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     * @param actorId 实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param startedAt 当前执行或执行段的开始时间。
     * @param deadlineAt 当前操作允许继续执行的截止时间。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次操作返回的整数结果。
     */
    int insertTurn(
            @Param("ownerKey") String ownerKey,
            @Param("turnId") String turnId,
            @Param("sessionId") String sessionId,
            @Param("requestId") String requestId,
            @Param("actorId") String actorId,
            @Param("status") String status,
            @Param("executorId") String executorId,
            @Param("startedAt") Timestamp startedAt,
            @Param("deadlineAt") Timestamp deadlineAt,
            @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /**
     * 更新满足当前映射条件的会话记录。 仅在会话尚未被活跃执行占用时更新。 查询或更新限定在传入的数据归属范围内。
     *
     * @param activeTurnId 会话当前占用的执行标识；无活跃执行时为空。
     * @param nextMessageSequence 会话下一条正式消息的序号，用于保持消息顺序。
     * @param lastMessageAt 会话最近一条正式消息的时间，供会话排序使用。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的整数结果。
     */
    int claimSessionForTurn(
            @Param("activeTurnId") String activeTurnId,
            @Param("nextMessageSequence") Long nextMessageSequence,
            @Param("lastMessageAt") Timestamp lastMessageAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId);

    /**
     * 更新满足当前映射条件的执行记录。 使用原版本条件防止覆盖并发更新。 查询或更新限定在传入的数据归属范围内。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param finishedAt 执行结束时间；尚未结束的记录可以没有该时间。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次操作返回的整数结果。
     */
    int transitionTurn(
            @Param("status") String status,
            @Param("executorId") String executorId,
            @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
            @Param("finishedAt") Timestamp finishedAt,
            @Param("failureCode") String failureCode,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("turnId") String turnId,
            @Param("version") Long version);

    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param activeTurnId 会话当前占用的执行标识；无活跃执行时为空。
     * @return 本次操作返回的整数结果。
     */
    int releaseSessionTurn(
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("activeTurnId") String activeTurnId);

    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param nextMessageSequence 会话下一条正式消息的序号，用于保持消息顺序。
     * @param lastMessageAt 会话最近一条正式消息的时间，供会话排序使用。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param activeTurnId 会话当前占用的执行标识；无活跃执行时为空。
     * @return 本次操作返回的整数结果。
     */
    int releaseSessionTurnWithMessage(
            @Param("nextMessageSequence") Long nextMessageSequence,
            @Param("lastMessageAt") Timestamp lastMessageAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("activeTurnId") String activeTurnId);

    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param activeTurnId 会话当前占用的执行标识；无活跃执行时为空。
     * @return 本次操作返回的整数结果。
     */
    int touchActiveSession(
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("activeTurnId") String activeTurnId);

    /**
     * 按映射语句的筛选与分页条件读取会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<SessionRow> selectFindSession(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);

    /**
     * 按映射语句的筛选与分页条件读取执行记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    List<TurnRow> selectFindTurn(
            @Param("ownerKey") String ownerKey, @Param("turnId") String turnId);

    /**
     * 按映射语句的筛选与分页条件读取执行记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<TurnRow> selectFindLatestTurn(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);

    /**
     * 按映射语句的筛选与分页条件读取会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param limit 本次处理或返回数量上限。
     * @param offset 本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     */
    List<SessionRow> selectListSessions(
            @Param("ownerKey") String ownerKey,
            @Param("status") String status,
            @Param("limit") Integer limit,
            @Param("offset") Integer offset);

    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param title 当前会话执行映射器的可读标题，供宿主界面展示。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @return 本次操作返回的整数结果。
     */
    int updateRenameSession(
            @Param("title") String title,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("status") String status);

    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param pinned 会话是否置顶，影响会话目录展示顺序。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @return 本次操作返回的整数结果。
     */
    int updateSetSessionPinned(
            @Param("pinned") Boolean pinned,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("status") String status);

    /**
     * 更新满足当前映射条件的会话记录。 仅在会话尚未被活跃执行占用时更新。 查询或更新限定在传入的数据归属范围内。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param expectedStatus 更新前必须匹配的原状态，用于拒绝已被其他执行修改的记录。
     * @return 本次操作返回的整数结果。
     */
    int updateArchiveSession(
            @Param("status") String status,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("expectedStatus") String expectedStatus);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<ConversationHistoryRow> selectListFinalMessages(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);

    /**
     * 按映射语句的筛选与分页条件读取执行记录。
     *
     * @param runningStatus 筛选仍在运行的执行状态。
     * @param cancellingStatus 筛选正在取消、尚未收敛到终态的执行状态。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    List<TurnRow> selectFindExpiredLeases(
            @Param("runningStatus") String runningStatus,
            @Param("cancellingStatus") String cancellingStatus,
            @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
            @Param("limit") Integer limit);

    /**
     * 按映射语句的筛选与分页条件读取执行记录。
     *
     * @param runningStatus 筛选仍在运行的执行状态。
     * @param waitingApprovalStatus 筛选等待工具审批的执行状态。
     * @param waitingAskUserStatus 筛选等待用户澄清回答的执行状态。
     * @param cancellingStatus 筛选正在取消、尚未收敛到终态的执行状态。
     * @param deadlineAt 当前操作允许继续执行的截止时间。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    List<TurnRow> selectFindOverdueTurns(
            @Param("runningStatus") String runningStatus,
            @Param("waitingApprovalStatus") String waitingApprovalStatus,
            @Param("waitingAskUserStatus") String waitingAskUserStatus,
            @Param("cancellingStatus") String cancellingStatus,
            @Param("deadlineAt") Timestamp deadlineAt,
            @Param("limit") Integer limit);

    /**
     * 写入新的会话记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param createdBy 当前会话执行映射器使用的创建按条件，供其处理与状态记录使用。
     * @param title 当前会话执行映射器的可读标题，供宿主界面展示。
     * @param lastMessageAt 会话最近一条正式消息的时间，供会话排序使用。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次操作返回的整数结果。
     */
    int insertSession(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("status") String status,
            @Param("createdBy") String createdBy,
            @Param("title") String title,
            @Param("lastMessageAt") Timestamp lastMessageAt,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /**
     * 按映射语句的筛选与分页条件读取会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<SessionRow> selectLockSession(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);

    /**
     * 按映射语句的筛选与分页条件读取执行记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    List<TurnRow> selectLockTurn(
            @Param("ownerKey") String ownerKey, @Param("turnId") String turnId);

    /**
     * 按映射语句的筛选与分页条件读取执行记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param requestId 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     * @return 本次处理得到的结果集合。
     */
    List<TurnRow> selectFindTurnByRequest(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("requestId") String requestId);

    /**
     * 按映射语句的筛选与分页条件读取会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的长整型结果。
     */
    Long selectNextMessageSequence(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);

    /**
     * 写入新的会话历史记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param recordId 历史或审计记录的标识，用于定位单条持久化事实。
     * @param messageSequence 会话正式消息的顺序号，与执行事件游标分开使用。
     * @param payloadJson 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次操作返回的整数结果。
     */
    int insertMessage(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId,
            @Param("recordId") String recordId,
            @Param("messageSequence") Long messageSequence,
            @Param("payloadJson") String payloadJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /**
     * 更新满足当前映射条件的执行记录。
     *
     * @param parameters 调用参数集合，由相应工具或协议转换器解释。
     * @return 本次操作返回的整数结果。
     */
    int renewLeases(Map<String, Object> parameters);
}
