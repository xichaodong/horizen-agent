package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.SessionRow;

import org.apache.ibatis.annotations.Param;

/**
 * JdbcSessionWorkspaceReleaseRepository 的数据库操作。
 */
public interface SessionWorkspaceReleaseMapper {
    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param projectId            工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey             宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param workspaceReleaseId   工作区发布的标识，用于关联相应记录或执行。
     * @param workspaceReleaseHash 工作区发布的内容摘要，供校验或去重使用。
     * @param ownerKey             宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId            会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的整数结果。
     */
    int bindWorkspaceReleaseIfAbsent(
            @Param("projectId") Long projectId,
            @Param("agentKey") String agentKey,
            @Param("workspaceReleaseId") Long workspaceReleaseId,
            @Param("workspaceReleaseHash") String workspaceReleaseHash,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId);

    /**
     * 按映射语句的筛选与分页条件读取会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的会话存储记录结果。
     */
    SessionRow selectBinding(
            @Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);
}
