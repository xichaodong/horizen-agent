package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.ConversationHistoryRow;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcTurnTimelineStore 的数据库操作。
 */
public interface TurnTimelineMapper {
    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    List<String> selectMessageIdsForTurn(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey   宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param recordType 历史记录类别，用于区分消息、过程事实与呈现块。
     * @param recordId   历史或审计记录的标识，用于定位单条持久化事实。
     * @param sessionId  会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId     单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    List<String> selectHistoryPayload(
            @Param("ownerKey") String ownerKey,
            @Param("recordType") String recordType,
            @Param("recordId") String recordId,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId);

    /**
     * 更新满足当前映射条件的会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param timelinePayloadJson 时间线负载的 JSON 表示，供持久化或协议转换使用。
     * @param timelineCreatedAt   时间线创建的时间，用于记录对应生命周期节点。
     * @param ownerKey            宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param recordType          历史记录类别，用于区分消息、过程事实与呈现块。
     * @param recordId            历史或审计记录的标识，用于定位单条持久化事实。
     * @param sessionId           会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId              单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次操作返回的整数结果。
     */
    int attachTimelineEvent(
            @Param("timelinePayloadJson") String timelinePayloadJson,
            @Param("timelineCreatedAt") Timestamp timelineCreatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("recordType") String recordType,
            @Param("recordId") String recordId,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey   宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param recordType 历史记录类别，用于区分消息、过程事实与呈现块。
     * @param recordId   历史或审计记录的标识，用于定位单条持久化事实。
     * @param sessionId  会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId     单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次操作返回的对话历史存储记录结果。
     */
    ConversationHistoryRow selectTimelineEvent(
            @Param("ownerKey") String ownerKey,
            @Param("recordType") String recordType,
            @Param("recordId") String recordId,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<ConversationHistoryRow> selectListForSession(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);

    /**
     * 写入新的会话历史记录，字段绑定由当前 SQL 映射明确指定。
     *
     * @param row 当前执行时间线映射器持有的存储记录对象，供相应处理步骤使用。
     * @return 本次操作返回的整数结果。
     */
    int insertEvent(@Param("row") ConversationHistoryRow row);
}
