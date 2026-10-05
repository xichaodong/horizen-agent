package dev.horizen.agent.adapter.agentscope.workspace.snapshot;

import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;

import java.io.InputStream;
import java.util.Objects;

/** AgentScope 适配器；存储实现由领域 Repository 接口隔离。 */
public final class RepositoryRemoteSnapshotClient implements RemoteSnapshotClient {
    /** 保存与恢复完整工作区归档的快照仓储。 */
    private final WorkspaceSnapshotRepository snapshots;

    /**
     * 创建仓储远端快照客户端，初始化该组件所需的状态、配置或依赖。
     *
     * @param snapshots 提供快照集合能力的依赖，具体实现由当前组件的组装方传入。
     */
    public RepositoryRemoteSnapshotClient(WorkspaceSnapshotRepository snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /**
     * 上传仓储远端快照客户端。
     *
     * @param id 目标对象的标识。
     * @param data 当前操作处理的数据。
     */
    @Override
    public void upload(String id, InputStream data) throws Exception {
        snapshots.upload(id, data);
    }

    /**
     * 下载仓储远端快照客户端。
     *
     * @param id 目标对象的标识。
     * @return 本次操作返回的输入事件流结果。
     */
    @Override
    public InputStream download(String id) throws Exception {
        return snapshots.download(id);
    }

    /**
     * 检查是否存在仓储远端快照客户端。
     *
     * @param id 目标对象的标识。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(String id) throws Exception {
        return snapshots.exists(id);
    }
}
