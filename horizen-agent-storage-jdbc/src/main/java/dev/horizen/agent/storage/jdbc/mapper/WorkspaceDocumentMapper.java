package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.WorkspaceFileRow;
import dev.horizen.agent.storage.jdbc.model.WorkspaceOperationRow;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** JdbcWorkspaceDocumentRepository 的数据库操作。 */
public interface WorkspaceDocumentMapper {
    /**
     * 按映射语句的筛选与分页条件读取工作区文件记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文档映射器使用的作用域键，供其处理与状态记录使用。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceFileRow> selectMetadata(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("pathHash") String pathHash);

    /**
     * 按映射语句的筛选与分页条件读取工作区文件记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文档映射器使用的作用域键，供其处理与状态记录使用。
     * @param filePath 当前工作区文档映射器使用的文件路径，供其处理与状态记录使用。
     * @param limit 本次处理或返回数量上限。
     * @param offset 本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceFileRow> selectList(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("filePath") String filePath,
            @Param("limit") Integer limit,
            @Param("offset") Integer offset);

    /**
     * 按映射语句的筛选与分页条件读取工作区操作记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文档映射器使用的作用域键，供其处理与状态记录使用。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @return 本次操作返回的整数结果。
     */
    Integer selectHasAppendOperation(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("pathHash") String pathHash,
            @Param("operationId") String operationId);

    /**
     * 删除满足当前映射条件的工作区文件记录。 使用原版本条件防止覆盖并发更新。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文档映射器使用的作用域键，供其处理与状态记录使用。
     * @param pathHash 路径的内容摘要，供校验或去重使用。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次操作返回的整数结果。
     */
    int updateDelete(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("pathHash") String pathHash,
            @Param("version") Long version);

    /**
     * 写入新的工作区文件记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param write 当前工作区文档映射器持有的写入对象，供相应处理步骤使用。
     * @return 本次操作返回的整数结果。
     */
    int updateInsert(WorkspaceFileRow write);

    /**
     * 更新满足当前映射条件的工作区文件记录。 使用原版本条件防止覆盖并发更新。 查询或更新限定在传入的数据归属范围内。
     *
     * @param write 当前工作区文档映射器持有的写入对象，供相应处理步骤使用。
     * @return 本次操作返回的整数结果。
     */
    int updateUpdate(WorkspaceFileRow write);

    /**
     * 写入新的工作区操作记录，字段绑定由当前 SQL 映射明确指定。 查询或更新限定在传入的数据归属范围内。
     *
     * @param write 当前工作区文档映射器持有的写入对象，供相应处理步骤使用。
     * @return 本次操作返回的整数结果。
     */
    int updateLog(WorkspaceOperationRow write);
}
