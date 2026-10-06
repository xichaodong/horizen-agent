package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.WorkspaceOperationRow;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcWorkspaceAuditRepository 的数据库操作。
 */
public interface WorkspaceAuditMapper {
    /**
     * 按映射语句的筛选与分页条件读取工作区操作记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey      宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey      宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey      当前工作区审计映射器使用的作用域键，供其处理与状态记录使用。
     * @param auditSequence 当前工作区审计映射器使用的审计序号，供其处理与状态记录使用。
     * @param limit         本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceOperationRow> selectScopeOperations(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("auditSequence") Long auditSequence,
            @Param("limit") Integer limit);

    /**
     * 按映射语句的筛选与分页条件读取工作区操作记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey      宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey      宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey      当前工作区审计映射器使用的作用域键，供其处理与状态记录使用。
     * @param pathHash      路径的内容摘要，供校验或去重使用。
     * @param auditSequence 当前工作区审计映射器使用的审计序号，供其处理与状态记录使用。
     * @param limit         本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceOperationRow> selectPathOperations(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("pathHash") String pathHash,
            @Param("auditSequence") Long auditSequence,
            @Param("limit") Integer limit);

    /**
     * 按映射语句的筛选与分页条件读取工作区操作记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey      宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey      宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey      当前工作区审计映射器使用的作用域键，供其处理与状态记录使用。
     * @param pathHash      路径的内容摘要，供校验或去重使用。
     * @param auditSequence 当前工作区审计映射器使用的审计序号，供其处理与状态记录使用。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceOperationRow> selectFind(
            @Param("ownerKey") String ownerKey,
            @Param("agentKey") String agentKey,
            @Param("scopeKey") String scopeKey,
            @Param("pathHash") String pathHash,
            @Param("auditSequence") Long auditSequence);

    /**
     * 按映射语句的筛选与分页条件读取会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey  宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @return 本次处理得到的结果集合。
     */
    List<String> selectOwnsMemory(
            @Param("ownerKey") String ownerKey,
            @Param("projectId") Long projectId,
            @Param("agentKey") String agentKey);
}
