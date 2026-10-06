package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.adapter.agentscope.workspace.document.WorkspaceDocumentBaseStore;
import dev.horizen.agent.adapter.agentscope.workspace.filesystem.SessionWorkspaceFilesystemSpec;
import dev.horizen.agent.adapter.agentscope.workspace.release.ReadOnlyWorkspaceFilesystem;
import dev.horizen.agent.adapter.agentscope.workspace.snapshot.NonCachedSandboxStateStore;
import dev.horizen.agent.context.HistoryContextRecoveryMiddleware;
import dev.horizen.agent.context.HistoryRecoveringAgentStateStore;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.SandboxSnapshotProperties;

import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import okhttp3.OkHttpClient;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 组装 AgentScope 云端记忆路由、分布式历史以及沙箱和文件系统。
 */
public final class WorkspaceRuntimeConfigurer {
    /**
     * Agent键的固定取值，用于相应策略和边界判断。
     */
    private static final String AGENT_KEY = AgentProperties.AGENT_KEY;

    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private WorkspaceRuntimeConfigurer() {
    }

    /**
     * 完成当前操作的configureDistributedWorkspace步骤，按实现更新相应状态或依赖。
     *
     * @param builder            当前工作区运行时配置器持有的构造器对象，供相应处理步骤使用。
     * @param distributedStore   共享的 Agent 工作状态存储，用于跨实例执行与恢复。
     * @param sessions           会话对象或会话索引，按相应的归属键定位数据。
     * @param sandbox            当前工作区运行时配置器持有的沙箱对象，供相应处理步骤使用。
     * @param workspaceDocuments 提供工作区文档集合能力的依赖，具体实现由当前组件的组装方传入。
     */
    public static void configureDistributedWorkspace(
            HarnessAgent.Builder builder,
            DistributedStore distributedStore,
            SessionTurnStore sessions,
            E2bSandboxProperties sandbox,
            WorkspaceDocumentRepository workspaceDocuments) {
        if (distributedStore == null) return;
        builder.distributedStore(distributedStore);
        if (sessions != null) {
            HistoryRecoveringAgentStateStore recovering =
                    new HistoryRecoveringAgentStateStore(
                            sandbox.isEnabled()
                                    ? new NonCachedSandboxStateStore(
                                    distributedStore.agentStateStore())
                                    : distributedStore.agentStateStore(),
                            sessions);
            builder.stateStore(recovering);
            builder.middleware(new HistoryContextRecoveryMiddleware(recovering));
        }
        if (workspaceDocuments != null) {
            // 上游记忆钩子使用实例本地的读取、合并和上传。云端 memory_save 通过操作 ID
            // 幂等追加替代此路径，自动钩子不能绕过它。
            builder.disableMemoryHooks();
        }
        if (!sandbox.isEnabled()) {
            builder.filesystem(new SessionWorkspaceFilesystemSpec());
            if (workspaceDocuments != null) {
                configureCloudMemoryRoutes(builder, workspaceDocuments);
            }
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前WorkspaceRuntimeConfigurer处理步骤使用。
     *
     * @param builder            当前工作区运行时配置器持有的构造器对象，供相应处理步骤使用。
     * @param distributedStore   共享的 Agent 工作状态存储，用于跨实例执行与恢复。
     * @param sandbox            当前工作区运行时配置器持有的沙箱对象，供相应处理步骤使用。
     * @param workspaceDocuments 提供工作区文档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param snapshots          当前工作区运行时配置器持有的快照集合对象，供相应处理步骤使用。
     * @param snapshotProperties 当前工作区运行时配置器持有的快照配置对象，供相应处理步骤使用。
     * @param workspaceRoot      执行工作区的根目录，用于解析任务文件与脚本路径。
     * @param published          已发布的状态标记，用于选择当前组件的处理路径。
     * @param http               提供HTTP能力的依赖，具体实现由当前组件的组装方传入。
     * @return 本次操作返回的沙箱上下文结果。
     */
    public static SandboxContext configureSandbox(
            HarnessAgent.Builder builder,
            DistributedStore distributedStore,
            E2bSandboxProperties sandbox,
            WorkspaceDocumentRepository workspaceDocuments,
            SandboxSnapshotSpec snapshots,
            SandboxSnapshotProperties snapshotProperties,
            Path workspaceRoot,
            boolean published,
            OkHttpClient http) {
        if (!sandbox.isEnabled()) {
            builder.disableShellTool();
            return null;
        }
        var spec = sandbox.toSpec();
        spec.httpClient(
                http.newBuilder()
                        .connectTimeout(sandbox.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                        .readTimeout(sandbox.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                        .build());
        if (published) spec.refreshPublishedWorkspace(true);
        if (snapshots != null) {
            spec.snapshotSpec(snapshots)
                    .snapshotLimits(
                            snapshotProperties.getMaxArchiveBytes(),
                            snapshotProperties.getMaxEntries(),
                            snapshotProperties.getTimeoutSeconds());
        }
        builder.filesystem(spec);
        if (workspaceDocuments != null) {
            configureCloudMemoryRoutes(builder, workspaceDocuments);
        }
        builder.disableTranscript();
        // 在 HarnessAgent 填充默认值前绑定上下文，使获取资源和检查点共享同一次调用的 RuntimeContext，
        // 避免使用 Harness 创建的私有副本。
        return spec.toSandboxContext(workspaceRoot);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param root 当前操作允许使用的根路径。
     * @return 本次操作返回的Abstract文件系统结果。
     */
    public static AbstractFilesystem publicationFilesystem(Path root) {
        return new ReadOnlyWorkspaceFilesystem(new LocalFilesystem(root, true, 10));
    }

    /**
     * 完成当前操作的configureCloudMemoryRoutes步骤，按实现更新相应状态或依赖。
     *
     * @param builder            当前工作区运行时配置器持有的构造器对象，供相应处理步骤使用。
     * @param workspaceDocuments 提供工作区文档集合能力的依赖，具体实现由当前组件的组装方传入。
     */
    public static void configureCloudMemoryRoutes(
            HarnessAgent.Builder builder, WorkspaceDocumentRepository workspaceDocuments) {
        builder.filesystemRoute("MEMORY.md", cloudMemoryFilesystem(workspaceDocuments, "root"));
    }

    /**
     * 记忆不存在本地覆盖层，云端缺失或故障不能导致本地所有者数据被暴露。
     */
    public static AbstractFilesystem sessionPlansFilesystem(BaseStore store) {
        return new RemoteFilesystem(
                store,
                rc -> {
                    if (rc == null
                            || rc.getUserId() == null
                            || rc.getUserId().isBlank()
                            || rc.getSessionId() == null
                            || rc.getSessionId().isBlank()) {
                        throw new IllegalStateException(
                                "Session file access requires trusted owner and Session");
                    }
                    var parent = rc.get(ToolInvocationScope.class);
                    if (parent != null && !rc.getUserId().equals(parent.getOwnerKey())) {
                        throw new SecurityException("Shared plan owner mismatch");
                    }
                    return List.of(
                            "agents",
                            AGENT_KEY,
                            "users",
                            rc.getUserId(),
                            "sessions",
                            parent == null ? rc.getSessionId() : parent.getSessionId(),
                            "plans");
                });
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param workspaceDocuments 提供工作区文档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param segment            当前工作区运行时配置器使用的执行段，供其处理与状态记录使用。
     * @return 本次操作返回的Abstract文件系统结果。
     */
    public static AbstractFilesystem cloudMemoryFilesystem(
            WorkspaceDocumentRepository workspaceDocuments, String segment) {
        return new RemoteFilesystem(
                new WorkspaceDocumentBaseStore(workspaceDocuments),
                rc -> {
                    String ownerKey = rc == null ? null : rc.getUserId();
                    if (ownerKey == null || ownerKey.isBlank()) ownerKey = "_default";
                    return List.of("agents", AGENT_KEY, "users", ownerKey, segment);
                });
    }
}
