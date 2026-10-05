package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.ConversationHistoryRow;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/** JdbcPresentationStore 的数据库操作。 */
public interface PresentationMapper {
    /**
     * 写入新的会话历史记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param recordId 历史或审计记录的标识，用于定位单条持久化事实。
     * @param payloadJson 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次操作返回的整数结果。
     */
    int updateCreateOrFind(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId,
            @Param("recordId") String recordId,
            @Param("payloadJson") String payloadJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param recordId 历史或审计记录的标识，用于定位单条持久化事实。
     * @return 本次处理得到的结果集合。
     */
    List<ConversationHistoryRow> selectFind(
            @Param("ownerKey") String ownerKey, @Param("recordId") String recordId);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<ConversationHistoryRow> selectListForSession(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);
}
