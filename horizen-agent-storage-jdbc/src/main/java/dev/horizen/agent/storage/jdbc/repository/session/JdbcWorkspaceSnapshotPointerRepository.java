package dev.horizen.agent.storage.jdbc.repository.session;

import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceSnapshotPointerMapper;

import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * 仅保存永久定位信息，不保存沙箱访问令牌或 AgentScope 上下文。
 */
public class JdbcWorkspaceSnapshotPointerRepository implements WorkspaceSnapshotPointerRepository {
    /**
     * 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    private final WorkspaceSnapshotPointerMapper mapper;

    /**
     * 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     */
    private final String executorId;

    /**
     * 创建JDBC工作区快照指针仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    public JdbcWorkspaceSnapshotPointerRepository(DataSource dataSource) {
        this(dataSource, null);
    }

    /**
     * 创建JDBC工作区快照指针仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     */
    public JdbcWorkspaceSnapshotPointerRepository(DataSource dataSource, String executorId) {
        this.mapper =
                MyBatisSessions.create(dataSource).getMapper(WorkspaceSnapshotPointerMapper.class);
        this.executorId = executorId;
    }

    /**
     * 供服务 IoC 容器注入依赖的构造方法。
     */
    public JdbcWorkspaceSnapshotPointerRepository(
            WorkspaceSnapshotPointerMapper mapper, String executorId) {
        this.mapper = Objects.requireNonNull(mapper);
        this.executorId = executorId;
    }

    /**
     * 查找快照标识。
     *
     * @param key 当前对象的查找或写入键。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<String> findSnapshotId(WorkspaceSnapshotKey key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(mapper.selectSnapshot(key.getOwnerKey(), key.getSessionId()));
    }

    /**
     * 检查compareAndSetCommitted对应的条件，供调用方选择后续处理分支。
     *
     * @param key      当前对象的查找或写入键。
     * @param expected 当前JDBC工作区快照指针仓储使用的预期，供其处理与状态记录使用。
     * @param next     当前JDBC工作区快照指针仓储使用的下一个，供其处理与状态记录使用。
     * @param turnId   单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次检查是否通过或本次更新是否成功。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public boolean compareAndSetCommitted(
            WorkspaceSnapshotKey key, String expected, String next, String turnId) {
        if (next == null || !next.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}"))
            throw new IllegalArgumentException("Invalid snapshotId");
        if (executorId == null)
            return mapper.compareAndSetSnapshot(
                    next, key.getOwnerKey(), key.getSessionId(), expected)
                    == 1;
        if (turnId == null)
            throw new IllegalStateException("Snapshot commit requires an execution identity");
        return mapper.compareAndSetSnapshotForActiveTurn(
                next,
                key.getOwnerKey(),
                key.getSessionId(),
                turnId,
                expected,
                turnId,
                executorId)
                == 1;
    }

    /**
     * 保存已提交。
     *
     * @param key        当前对象的查找或写入键。
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void saveCommitted(WorkspaceSnapshotKey key, String snapshotId) {
        Objects.requireNonNull(key, "key");
        if (snapshotId == null || !snapshotId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}")) {
            throw new IllegalArgumentException("Invalid snapshotId");
        }
        int updated = mapper.updateSnapshot(snapshotId, key.getOwnerKey(), key.getSessionId());
        if (updated != 1 && findSnapshotId(key).filter(snapshotId::equals).isEmpty()) {
            throw new IllegalStateException("Workspace Session does not exist");
        }
    }
}
