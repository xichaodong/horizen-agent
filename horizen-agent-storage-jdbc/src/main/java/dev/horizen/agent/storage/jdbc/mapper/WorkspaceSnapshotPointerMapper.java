package dev.horizen.agent.storage.jdbc.mapper;

import org.apache.ibatis.annotations.Param;

/** JdbcWorkspaceSnapshotPointerRepository 的数据库操作。 */
public interface WorkspaceSnapshotPointerMapper {
    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param expectedSnapshotId 更新前必须匹配的快照标识；为 null 时只允许替换尚未绑定快照的会话。
     * @return 本次操作返回的整数结果。
     */
    int compareAndSetSnapshot(
            @Param("snapshotId") String snapshotId,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("expectedSnapshotId") String expectedSnapshotId);

    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param activeTurnId 会话当前占用的执行标识；无活跃执行时为空。
     * @param expectedSnapshotId 更新前必须匹配的快照标识；为 null 时只允许替换尚未绑定快照的会话。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @return 本次操作返回的整数结果。
     */
    int compareAndSetSnapshotForActiveTurn(
            @Param("snapshotId") String snapshotId,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId,
            @Param("activeTurnId") String activeTurnId,
            @Param("expectedSnapshotId") String expectedSnapshotId,
            @Param("turnId") String turnId,
            @Param("executorId") String executorId);

    /**
     * 更新满足当前映射条件的会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的整数结果。
     */
    int updateSnapshot(
            @Param("snapshotId") String snapshotId,
            @Param("ownerKey") String ownerKey,
            @Param("sessionId") String sessionId);

    /**
     * 按映射语句的筛选与分页条件读取会话记录。 查询或更新限定在传入的数据归属范围内。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理生成或读取的文本。
     */
    String selectSnapshot(@Param("ownerKey") String ownerKey, @Param("sessionId") String sessionId);
}
