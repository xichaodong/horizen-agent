package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.InteractionRow;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/** JdbcAskUserStore 的数据库操作。 */
public interface AskUserMapper {
    /**
     * 写入新的交互记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param interactionId 交互的标识，用于关联相应记录或执行。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param replyId 回复的标识，用于关联相应记录或执行。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param requestJson 请求的 JSON 表示，供持久化或协议转换使用。
     * @param responseJson 响应的 JSON 表示，供持久化或协议转换使用。
     * @param expiresAt 当前记录或授权的失效时间，用于过期检查。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次操作返回的整数结果。
     */
    int updateCreateOrFind(
            @Param("ownerKey") String ownerKey,
            @Param("interactionId") String interactionId,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId,
            @Param("replyId") String replyId,
            @Param("toolCallId") String toolCallId,
            @Param("requestJson") String requestJson,
            @Param("responseJson") String responseJson,
            @Param("expiresAt") Timestamp expiresAt,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /**
     * 按映射语句的筛选与分页条件读取交互记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param interactionId 交互的标识，用于关联相应记录或执行。
     * @return 本次处理得到的结果集合。
     */
    List<InteractionRow> selectFind(
            @Param("ownerKey") String ownerKey, @Param("interactionId") String interactionId);

    /**
     * 按映射语句的筛选与分页条件读取交互记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @return 本次处理得到的结果集合。
     */
    List<InteractionRow> selectFindByTool(
            @Param("ownerKey") String ownerKey,
            @Param("turnId") String turnId,
            @Param("toolCallId") String toolCallId);

    /**
     * 按映射语句的筛选与分页条件读取交互记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    List<InteractionRow> selectFindPending(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId);

    /**
     * 更新满足当前映射条件的交互记录。 使用原版本条件防止覆盖并发更新。 查询或更新限定在传入的数据归属范围内。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param responseJson 响应的 JSON 表示，供持久化或协议转换使用。
     * @param resolvedAt 已解析的时间，用于记录对应生命周期节点。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param interactionId 交互的标识，用于关联相应记录或执行。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次操作返回的整数结果。
     */
    int updateResolve(
            @Param("status") String status,
            @Param("responseJson") String responseJson,
            @Param("resolvedAt") Timestamp resolvedAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("ownerKey") String ownerKey,
            @Param("interactionId") String interactionId,
            @Param("version") Long version);
}
