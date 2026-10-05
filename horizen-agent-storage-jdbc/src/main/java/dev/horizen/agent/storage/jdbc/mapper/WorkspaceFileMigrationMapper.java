package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.WorkspaceDocumentRow;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** JdbcWorkspaceFileMigration 的数据库操作。 */
public interface WorkspaceFileMigrationMapper {
    /**
     * 按映射语句的筛选与分页条件读取ha_workspace_document记录。
     *
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceDocumentRow> selectMigrateBatch(@Param("limit") Integer limit);

    /**
     * 写入新的工作区文件记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文件迁移映射器使用的作用域键，供其处理与状态记录使用。
     * @param workspaceArea 当前工作区文件迁移映射器使用的工作区Area，供其处理与状态记录使用。
     * @param fileKind 当前工作区文件迁移映射器使用的文件类别，供其处理与状态记录使用。
     * @param writePolicy 当前工作区文件迁移映射器使用的写入策略，供其处理与状态记录使用。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @param filePath 当前工作区文件迁移映射器使用的文件路径，供其处理与状态记录使用。
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @param checksumSha256 内容的 SHA-256 校验值，用于完整性校验。
     * @param sizeBytes 内容大小，单位为字节。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次操作返回的整数结果。
     */
    int updateMigrateFile(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("workspaceArea") String workspaceArea,
            @Param("fileKind") String fileKind,
            @Param("writePolicy") String writePolicy,
            @Param("pathHash") String pathHash,
            @Param("filePath") String filePath,
            @Param("contentRef") String contentRef,
            @Param("checksumSha256") String checksumSha256,
            @Param("sizeBytes") Long sizeBytes,
            @Param("version") Long version,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /**
     * 按映射语句的筛选与分页条件读取ha_workspace_document_append记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文件迁移映射器使用的作用域键，供其处理与状态记录使用。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @return 本次处理得到的结果集合。
     */
    List<Map<String, Object>> selectMigrateOperationIds(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("pathHash") String pathHash);

    /**
     * 写入新的工作区操作记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文件迁移映射器使用的作用域键，供其处理与状态记录使用。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @param filePath 当前工作区文件迁移映射器使用的文件路径，供其处理与状态记录使用。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param appliedVersion 已应用的版本，供兼容或并发检查使用。
     * @param createdAt 当前记录的创建时间。
     * @return 本次操作返回的整数结果。
     */
    int updateMigrateOperationIds(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("pathHash") String pathHash,
            @Param("filePath") String filePath,
            @Param("operationId") Object operationId,
            @Param("appliedVersion") Object appliedVersion,
            @Param("createdAt") Object createdAt);

    /**
     * 写入新的工作区操作记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文件迁移映射器使用的作用域键，供其处理与状态记录使用。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @param filePath 当前工作区文件迁移映射器使用的文件路径，供其处理与状态记录使用。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param operationType 当前工作区文件迁移映射器使用的操作类型，供其处理与状态记录使用。
     * @param changeJson change的 JSON 表示，供持久化或协议转换使用。
     * @param appliedVersion 已应用的版本，供兼容或并发检查使用。
     * @param createdAt 当前记录的创建时间。
     * @return 本次操作返回的整数结果。
     */
    int updateInsertAudit(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("pathHash") String pathHash,
            @Param("filePath") String filePath,
            @Param("operationId") String operationId,
            @Param("operationType") String operationType,
            @Param("changeJson") String changeJson,
            @Param("appliedVersion") Long appliedVersion,
            @Param("createdAt") Timestamp createdAt);
}
