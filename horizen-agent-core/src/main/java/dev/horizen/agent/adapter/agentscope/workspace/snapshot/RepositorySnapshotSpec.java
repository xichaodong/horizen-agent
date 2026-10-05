package dev.horizen.agent.adapter.agentscope.workspace.snapshot;

import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import lombok.Getter;

/** 沙箱和非沙箱执行视图共用的云端归档提供器。 */
public final class RepositorySnapshotSpec implements SandboxSnapshotSpec {
    /** 当前组件依赖的领域仓储，隔离实际持久化实现。 */
    @Getter private final WorkspaceSnapshotRepository repository;

    /** 被包装的原始实现，由本组件补充隔离、观测或恢复行为。 */
    private final RemoteSnapshotSpec delegate;

    /**
     * 创建仓储快照规范，初始化该组件所需的状态、配置或依赖。
     *
     * @param repository 当前组件依赖的领域仓储，隔离实际持久化实现。
     */
    public RepositorySnapshotSpec(WorkspaceSnapshotRepository repository) {
        this.repository = repository;
        delegate = new RemoteSnapshotSpec(new RepositoryRemoteSnapshotClient(repository));
    }

    /**
     * 构造仓储快照规范。
     *
     * @param id 目标对象的标识。
     * @return 本次操作返回的沙箱快照结果。
     */
    @Override
    public SandboxSnapshot build(String id) {
        return delegate.build(id);
    }
}
