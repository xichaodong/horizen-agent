package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.WorkspaceFileRow;
import dev.horizen.agent.storage.jdbc.model.WorkspaceOperationRow;
import dev.horizen.agent.storage.jdbc.model.WorkspaceReleaseRow;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** JdbcWorkspaceCatalogRepository 的数据库操作。 */
public interface WorkspaceCatalogMapper {
    /**
     * 按映射语句的筛选与分页条件读取ha_workspace记录。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @return 本次处理得到的结果集合。
     */
    List<Long> selectDraft(@Param("projectId") Long projectId, @Param("agentKey") String agentKey);

    /**
     * 按映射语句的筛选与分页条件读取工作区文件记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceFileRow> selectFiles(
            @Param("ownerKey") String ownerKey, @Param("agentKey") String agentKey);

    /**
     * 写入新的ha_workspace记录，字段绑定由当前 SQL 映射明确指定。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @return 本次操作返回的整数结果。
     */
    int updateLock(@Param("projectId") Long projectId, @Param("agentKey") String agentKey);

    /**
     * 按映射语句的筛选与分页条件读取ha_workspace记录。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @return 本次处理得到的结果集合。
     */
    List<Map<String, Object>> selectLock(
            @Param("projectId") Long projectId, @Param("agentKey") String agentKey);

    /**
     * 更新满足当前映射条件的ha_workspace记录。 使用原版本条件防止覆盖并发更新。
     *
     * @param updatedBy 当前工作区目录映射器使用的更新按条件，供其处理与状态记录使用。
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次操作返回的整数结果。
     */
    int updateSave(
            @Param("updatedBy") String updatedBy,
            @Param("projectId") Long projectId,
            @Param("agentKey") String agentKey,
            @Param("version") Long version);

    /**
     * 写入新的工作区文件记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param fileKind 当前工作区目录映射器使用的文件类别，供其处理与状态记录使用。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @param filePath 当前工作区目录映射器使用的文件路径，供其处理与状态记录使用。
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @param checksumSha256 内容的 SHA-256 校验值，用于完整性校验。
     * @param sizeBytes 内容大小，单位为字节。
     * @param mediaType 当前工作区目录映射器使用的媒体类型，供其处理与状态记录使用。
     * @return 本次操作返回的整数结果。
     */
    int updateSave2(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("fileKind") String fileKind,
            @Param("pathHash") String pathHash,
            @Param("filePath") String filePath,
            @Param("contentRef") String contentRef,
            @Param("checksumSha256") String checksumSha256,
            @Param("sizeBytes") Long sizeBytes,
            @Param("mediaType") String mediaType);

    /**
     * 删除满足当前映射条件的工作区文件记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @return 本次操作返回的整数结果。
     */
    int updateSave3(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("pathHash") String pathHash);

    /**
     * 按映射语句的筛选与分页条件读取工作区文件记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @return 本次操作返回的长整型结果。
     */
    Long selectFileVersion(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("pathHash") String pathHash);

    /**
     * 写入新的工作区操作记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param write 当前工作区目录映射器持有的写入对象，供相应处理步骤使用。
     * @return 本次操作返回的整数结果。
     */
    int updateAudit(WorkspaceOperationRow write);

    /**
     * 按映射语句的筛选与分页条件读取ha_workspace记录。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @return 本次操作返回的长整型结果。
     */
    Long selectPublish(@Param("projectId") Long projectId, @Param("agentKey") String agentKey);

    /**
     * 写入新的工作区发布记录，字段绑定由当前 SQL 映射明确指定。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param releaseNo 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。
     * @param releaseHash 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @param manifestJson 清单的 JSON 表示，供持久化或协议转换使用。
     * @param notes 当前工作区目录映射器使用的说明集合，供其处理与状态记录使用。
     * @param createdBy 当前工作区目录映射器使用的创建按条件，供其处理与状态记录使用。
     * @return 本次操作返回的整数结果。
     */
    int updatePublish(
            @Param("projectId") Long projectId,
            @Param("agentKey") String agentKey,
            @Param("releaseNo") Long releaseNo,
            @Param("releaseHash") String releaseHash,
            @Param("manifestJson") String manifestJson,
            @Param("notes") String notes,
            @Param("createdBy") String createdBy);

    /**
     * 更新满足当前映射条件的ha_workspace记录。 使用原版本条件防止覆盖并发更新。
     *
     * @param currentReleaseId 当前发布的标识，用于关联相应记录或执行。
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次操作返回的整数结果。
     */
    int updatePublish2(
            @Param("currentReleaseId") Long currentReleaseId,
            @Param("projectId") Long projectId,
            @Param("agentKey") String agentKey,
            @Param("version") Long version);

    /**
     * 按映射语句的筛选与分页条件读取工作区发布记录。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param releaseNo 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceReleaseRow> selectByNumber(
            @Param("projectId") Long projectId,
            @Param("agentKey") String agentKey,
            @Param("releaseNo") Long releaseNo);

    /**
     * 按映射语句的筛选与分页条件读取工作区发布记录。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceReleaseRow> selectCurrent(
            @Param("projectId") Long projectId, @Param("agentKey") String agentKey);

    /**
     * 按映射语句的筛选与分页条件读取工作区发布记录。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param id 目标对象的标识。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceReleaseRow> selectById(
            @Param("projectId") Long projectId,
            @Param("agentKey") String agentKey,
            @Param("id") Long id);

    /**
     * 按映射语句的筛选与分页条件读取工作区发布记录。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceReleaseRow> selectReleases(
            @Param("projectId") Long projectId, @Param("agentKey") String agentKey);
}
