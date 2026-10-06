package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.ArtifactRow;
import dev.horizen.agent.storage.jdbc.model.ConversationHistoryRow;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** JdbcArtifactStore 的数据库操作。 */
public interface ArtifactMapper {
    /**
     * 写入新的ha_artifact记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param kind 当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param title 当前产物映射器的可读标题，供宿主界面展示。
     * @param mediaType 当前产物映射器使用的媒体类型，供其处理与状态记录使用。
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @param sizeBytes 内容大小，单位为字节。
     * @param checksumSha256 内容的 SHA-256 校验值，用于完整性校验。
     * @param parentArtifactId 来源产物的标识，用于串联修改前后的版本关系。
     * @param source 待解析或转换的来源对象。
     * @param sourceRef 来源资源的引用，供追踪产物或事件的生成来源。
     * @param expiresAt 当前记录或授权的失效时间，用于过期检查。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param deletedAt 记录进入删除状态的时间，供生命周期和审计查询使用。
     * @return 本次操作返回的整数结果。
     */
    int insertArtifact(
            @Param("ownerKey") String ownerKey,
            @Param("artifactId") String artifactId,
            @Param("kind") String kind,
            @Param("status") String status,
            @Param("title") String title,
            @Param("mediaType") String mediaType,
            @Param("contentRef") String contentRef,
            @Param("sizeBytes") Long sizeBytes,
            @Param("checksumSha256") String checksumSha256,
            @Param("parentArtifactId") String parentArtifactId,
            @Param("source") String source,
            @Param("sourceRef") String sourceRef,
            @Param("expiresAt") Timestamp expiresAt,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("deletedAt") Timestamp deletedAt);

    /**
     * 按映射语句的筛选与分页条件读取ha_artifact记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<ArtifactRow> selectFind(
            @Param("ownerKey") String ownerKey, @Param("artifactId") String artifactId);

    /**
     * 更新满足当前映射条件的ha_artifact记录。 使用原版本条件防止覆盖并发更新。 查询或更新限定在传入的数据归属范围内。
     *
     * @param kind 当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param title 当前产物映射器的可读标题，供宿主界面展示。
     * @param mediaType 当前产物映射器使用的媒体类型，供其处理与状态记录使用。
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @param sizeBytes 内容大小，单位为字节。
     * @param checksumSha256 内容的 SHA-256 校验值，用于完整性校验。
     * @param parentArtifactId 来源产物的标识，用于串联修改前后的版本关系。
     * @param source 待解析或转换的来源对象。
     * @param sourceRef 来源资源的引用，供追踪产物或事件的生成来源。
     * @param expiresAt 当前记录或授权的失效时间，用于过期检查。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @param deletedAt 记录进入删除状态的时间，供生命周期和审计查询使用。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次操作返回的整数结果。
     */
    int updateArtifact(
            @Param("kind") String kind,
            @Param("status") String status,
            @Param("title") String title,
            @Param("mediaType") String mediaType,
            @Param("contentRef") String contentRef,
            @Param("sizeBytes") Long sizeBytes,
            @Param("checksumSha256") String checksumSha256,
            @Param("parentArtifactId") String parentArtifactId,
            @Param("source") String source,
            @Param("sourceRef") String sourceRef,
            @Param("expiresAt") Timestamp expiresAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("deletedAt") Timestamp deletedAt,
            @Param("ownerKey") String ownerKey,
            @Param("artifactId") String artifactId,
            @Param("version") Long version);

    /**
     * 写入新的会话历史记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param recordId 历史或审计记录的标识，用于定位单条持久化事实。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param artifactRole 当前产物映射器使用的产物角色，供其处理与状态记录使用。
     * @param payloadJson 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次操作返回的整数结果。
     */
    int insertArtifactReference(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId,
            @Param("recordId") String recordId,
            @Param("artifactId") String artifactId,
            @Param("artifactRole") String artifactRole,
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
    List<ConversationHistoryRow> selectArtifactReference(
            @Param("ownerKey") String ownerKey, @Param("recordId") String recordId);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    List<Map<String, Object>> selectUserMessagesForTurn(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param historySequence 持久会话时间线的顺序号，用于分页和历史排列。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @return 本次处理生成或读取的文本。
     */
    String selectMessagePayloadForUpdate(
            @Param("historySequence") Object historySequence, @Param("ownerKey") String ownerKey);

    /**
     * 更新满足当前映射条件的会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param payloadJson 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param recordId 历史或审计记录的标识，用于定位单条持久化事实。
     * @return 本次操作返回的整数结果。
     */
    int updateMessagePayload(
            @Param("payloadJson") String payloadJson,
            @Param("ownerKey") String ownerKey,
            @Param("recordId") Object recordId);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<ConversationHistoryRow> selectListReferences(
            @Param("ownerKey") String ownerKey, @Param("artifactId") String artifactId);

    /**
     * 按映射语句的筛选与分页条件读取会话历史记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<ConversationHistoryRow> selectListReferencesForSession(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);

    /**
     * 按映射语句的筛选与分页条件读取ha_artifact记录。
     *
     * @param ownerKey 同时限定消息历史和产物元数据的归属范围。
     * @param sessionId 只读取该会话中引用过的产物。
     * @param readyStatus 必须匹配的产物就绪状态。
     * @param limit 最多返回的产物数量。
     * @param offset 分页时跳过的产物数量。
     * @param outputsOnly 是否只列出 OUTPUT 引用对应的产物。
     * @return 本次处理得到的结果集合。
     */
    List<ArtifactRow> selectReady(
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("readyStatus") String readyStatus,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("outputsOnly") boolean outputsOnly);
}
