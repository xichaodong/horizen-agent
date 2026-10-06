package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.adapter.agentscope.artifact.ArtifactDeliveryBridge;
import dev.horizen.agent.adapter.agentscope.artifact.ArtifactInputService;
import dev.horizen.agent.adapter.agentscope.artifact.ArtifactTurnInputService;
import dev.horizen.agent.adapter.agentscope.workspace.snapshot.RepositorySnapshotSpec;
import dev.horizen.agent.adapter.skill.horizen.AgentScopeSkillRepositoryAdapter;
import dev.horizen.agent.adapter.skill.horizen.HorizenSkillReleaseClient;
import dev.horizen.agent.adapter.workspace.horizen.HorizenAgentReleaseRepository;
import dev.horizen.agent.application.workspace.AgentReleaseService;
import dev.horizen.agent.application.workspace.WorkspaceManagementService;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;
import dev.horizen.agent.observability.horizen.HorizenHttpBatchExporter;
import dev.horizen.agent.observability.horizen.HorizenTraceConfig;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.storage.bos.BosArtifactContentStore;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.AgentWorkspaceProperties;
import dev.horizen.agent.web.config.ArtifactProperties;
import dev.horizen.agent.web.config.ContextProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.HorizenProperties;
import dev.horizen.agent.web.config.MultimodalProperties;
import dev.horizen.agent.web.config.RuntimeStorageProperties;
import dev.horizen.agent.web.config.SandboxSnapshotProperties;
import dev.horizen.agent.web.config.SkillReleaseProperties;
import dev.horizen.agent.web.config.TurnEventProperties;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import io.agentscope.harness.agent.IsolationScope;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.URI;
import java.net.http.HttpClient;

/**
 * 组装运行时、模型和工具；API 与持久化组件由独立 IoC 配置组装。
 */
@Configuration(proxyBeanMethods = false)
public class AgentHostConfiguration {
    /**
     * Agent键的固定取值，用于相应策略和边界判断。
     */
    private static final String AGENT_KEY = AgentProperties.AGENT_KEY;

    /**
     * 计算或取得本方法声明的结果，供当前AgentHostConfiguration处理步骤使用。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param trace 当前Agent宿主组装持有的Trace对象，供相应处理步骤使用。
     * @return 本次操作返回的HorizenTrace配置结果。
     */
    @Bean
    HorizenTraceConfig traceConfig(AgentProperties agent, HorizenProperties trace) {
        return agent.ready() ? trace.toConfig() : null;
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentHostConfiguration处理步骤使用。
     *
     * @param config 当前组件的配置与策略参数。
     * @param client 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @return 本次操作返回的HorizenHTTP批次上报器结果。
     */
    @Bean(destroyMethod = "close")
    HorizenHttpBatchExporter traceExporter(
            ObjectProvider<HorizenTraceConfig> config,
            @Qualifier("traceHttpClient") HttpClient client) {
        var settings = config.getIfAvailable();
        return settings == null
                ? null
                : new HorizenHttpBatchExporter(settings, JsonUtils.newMapper(), client);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentHostConfiguration处理步骤使用。
     *
     * @param storage    当前Agent宿主组装持有的存储对象，供相应处理步骤使用。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的产物支持结果。
     */
    @Bean
    ArtifactSupport artifactSupport(
            ObjectProvider<RuntimeStorage> storage, ArtifactProperties properties) {
        return createArtifactSupport(storage.getIfAvailable(), properties);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentHostConfiguration处理步骤使用。
     *
     * @param storage    当前Agent宿主组装持有的存储对象，供相应处理步骤使用。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的Redis执行事件桥接器结果。
     */
    @Bean(destroyMethod = "close")
    RedisTurnEventBridge distributedEvents(
            ObjectProvider<RuntimeStorage> storage, TurnEventProperties properties) {
        var data = storage.getIfAvailable();
        return data == null ? null : new RedisTurnEventBridge(data.messageBus(), properties);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param agent      当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param workspace  当前Agent宿主组装持有的工作区对象，供相应处理步骤使用。
     * @param settings   当前Agent宿主组装持有的settings对象，供相应处理步骤使用。
     * @param storage    当前Agent宿主组装持有的存储对象，供相应处理步骤使用。
     * @param management 当前Agent宿主组装持有的管理对象，供相应处理步骤使用。
     * @param catalog    当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param contents   资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @param http       提供HTTP能力的依赖，具体实现由当前组件的组装方传入。
     * @param readers    当前Agent宿主组装持有的readers对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent发布服务结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Bean
    AgentReleaseService publicationReleases(
            AgentProperties agent,
            AgentWorkspaceProperties workspace,
            RuntimeStorageProperties settings,
            ObjectProvider<RuntimeStorage> storage,
            ObjectProvider<WorkspaceManagementService> management,
            ObjectProvider<WorkspaceCatalogRepository> catalog,
            ObjectProvider<WorkspaceContentRepository> contents,
            @Qualifier("publicationHttpClient") HttpClient http,
            @Qualifier("publicationBodyReaders") ThreadPoolTaskExecutor readers) {
        workspace.validate();
        if (!workspace.isEnabled() || !agent.ready()) return null;
        if (!settings.distributed())
            throw new IllegalArgumentException(
                    "Published workspaces require distributed runtime storage");
        boolean local = workspace.getSource() == AgentWorkspaceProperties.Source.LOCAL;
        if (local && management.getIfAvailable() == null)
            throw new IllegalArgumentException(
                    "LOCAL workspace publications require workspace-management.enabled");
        var client =
                local
                        ? null
                        : new HorizenSkillReleaseClient(
                        URI.create(workspace.getEndpoint()),
                        workspace.getToken(),
                        workspace.getArtifactHosts(),
                        workspace.isAllowHttp(),
                        workspace.getRequestTimeout(),
                        20L * 1024 * 1024,
                        http,
                        readers.getThreadPoolExecutor());
        return new AgentReleaseService(
                new AgentCatalogKey(workspace.getProjectId(), AGENT_KEY),
                new HorizenAgentReleaseRepository(
                        client,
                        workspace.getCacheDirectory().resolve("cache"),
                        workspace.getMaxCacheBytes(),
                        local ? catalog.getObject() : null,
                        local ? contents.getObject() : null),
                storage.getObject().getWorkspaceReleases());
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentHostConfiguration处理步骤使用。
     *
     * @param agent          当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context        当前执行上下文，提供关联标识和宿主绑定信息。
     * @param gateway        外部工具目录与调用的网关适配器。
     * @param sandbox        当前Agent宿主组装持有的沙箱对象，供相应处理步骤使用。
     * @param settings       当前Agent宿主组装持有的settings对象，供相应处理步骤使用。
     * @param multimodal     当前Agent宿主组装持有的多模态对象，供相应处理步骤使用。
     * @param snapshots      当前Agent宿主组装持有的快照集合对象，供相应处理步骤使用。
     * @param workspace      当前Agent宿主组装持有的工作区对象，供相应处理步骤使用。
     * @param skills         当前Agent宿主组装持有的Skill集合对象，供相应处理步骤使用。
     * @param storage        当前Agent宿主组装持有的存储对象，供相应处理步骤使用。
     * @param artifacts      产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param archives       当前Agent宿主组装持有的归档集合对象，供相应处理步骤使用。
     * @param tracing        当前Agent宿主组装持有的观测对象，供相应处理步骤使用。
     * @param exporter       当前Agent宿主组装持有的上报器对象，供相应处理步骤使用。
     * @param publications   当前Agent宿主组装持有的发布集合对象，供相应处理步骤使用。
     * @param infrastructure 当前Agent宿主组装持有的基础设施对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent运行时结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Bean(destroyMethod = "close")
    AgentRuntime agentRuntime(
            AgentProperties agent,
            ContextProperties context,
            GatewayProperties gateway,
            E2bSandboxProperties sandbox,
            RuntimeStorageProperties settings,
            MultimodalProperties multimodal,
            SandboxSnapshotProperties snapshots,
            AgentWorkspaceProperties workspace,
            SkillReleaseProperties skills,
            ObjectProvider<RuntimeStorage> storage,
            ObjectProvider<ArtifactSupport> artifacts,
            ObjectProvider<WorkspaceSnapshotRepository> archives,
            ObjectProvider<HorizenTraceConfig> tracing,
            ObjectProvider<HorizenHttpBatchExporter> exporter,
            ObjectProvider<AgentReleaseService> publications,
            RuntimeInfrastructure infrastructure) {
        if (workspace.isEnabled() && !settings.distributed())
            throw new IllegalArgumentException(
                    "Published workspaces require distributed runtime storage");
        if (snapshots.isEnabled() && !settings.distributed())
            throw new IllegalArgumentException("Cloud workspaces require distributed storage");
        if (settings.distributed()
                && sandbox.isEnabled()
                && sandbox.getIsolationScope() != IsolationScope.SESSION)
            throw new IllegalArgumentException(
                    "Cloud Session workspaces require SESSION sandbox isolation");
        if (settings.distributed()
                && settings.getSessionStateTtl().compareTo(agent.getStreamTimeout()) <= 0)
            throw new IllegalArgumentException(
                    "sessionStateTtl 必须大于 streamTimeout，避免运行中的 Turn 状态过期");
        if (skills.isEnabled() && !workspace.isEnabled())
            throw new IllegalArgumentException(
                    "Standalone Skill Session binding has been removed; configure workspace-release instead");
        if (!agent.ready()) return null;
        var data = storage.getIfAvailable();
        var files = artifacts.getIfAvailable();
        var trace = tracing.getIfAvailable();
        var sink = exporter.getIfAvailable();
        WorkspaceSnapshotRepository archive = data == null ? null : archives.getObject();
        AgentRuntime execution =
                AgentRuntimeFactory.create(
                        RuntimeAssembly.builder()
                                .properties(agent)
                                .contextProperties(context)
                                .gatewayProperties(gateway)
                                .sandboxProperties(sandbox)
                                .multimodalProperties(multimodal)
                                .traceConfig(trace)
                                .horizenExporter(sink)
                                .skillRepository(null)
                                .distributedStore(data == null ? null : data.getDistributedStore())
                                .sessionTurns(data == null ? null : data.getSessionTurns())
                                .artifactSupport(files)
                                .askUsers(data == null ? null : data.getAskUsers())
                                .workspaceDocuments(
                                        data == null ? null : data.getWorkspaceDocuments())
                                .snapshots(
                                        archive == null
                                                ? null
                                                : new RepositorySnapshotSpec(archive))
                                .snapshotProperties(snapshots)
                                .snapshotPointers(data == null ? null : data.getSnapshotPointers())
                                .infrastructure(infrastructure)
                                .build());
        var release = publications.getIfAvailable();
        if (release != null)
            execution =
                    new PublishedAgentRuntime(
                            release,
                            execution,
                            workspace.getCacheDirectory().resolve("executions"),
                            workspace.getMaxConcurrentExecutions(),
                            (version, root) ->
                                    AgentRuntimeFactory.create(
                                            RuntimeAssembly.builder()
                                                    .properties(agent)
                                                    .contextProperties(context)
                                                    .gatewayProperties(gateway)
                                                    .sandboxProperties(sandbox)
                                                    .multimodalProperties(multimodal)
                                                    .traceConfig(trace)
                                                    .horizenExporter(sink)
                                                    .skillRepository(
                                                            new AgentScopeSkillRepositoryAdapter())
                                                    .distributedStore(data.getDistributedStore())
                                                    .sessionTurns(data.getSessionTurns())
                                                    .artifactSupport(files)
                                                    .askUsers(data.getAskUsers())
                                                    .workspaceDocuments(
                                                            data.getWorkspaceDocuments())
                                                    .snapshots(
                                                            archive == null
                                                                    ? null
                                                                    : new RepositorySnapshotSpec(
                                                                    archive))
                                                    .snapshotProperties(snapshots)
                                                    .snapshotPointers(data.getSnapshotPointers())
                                                    .publishedWorkspace(root)
                                                    .infrastructure(infrastructure)
                                                    .build()));
        return execution;
    }

    /**
     * 创建产物支持。
     *
     * @param storage    当前Agent宿主组装持有的存储对象，供相应处理步骤使用。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的产物支持结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static ArtifactSupport createArtifactSupport(
            RuntimeStorage storage, ArtifactProperties properties) {
        if (properties == null || !properties.isEnabled()) return null;
        if (storage == null) {
            throw new IllegalStateException("BOS Artifact store requires distributed storage");
        }
        ArtifactContentStore contents = new BosArtifactContentStore(properties.toConfig());
        ArtifactLifecycleService lifecycle =
                new ArtifactLifecycleService(storage.getArtifacts(), contents);
        return new ArtifactSupport(
                new ArtifactDeliveryBridge(lifecycle),
                new ArtifactInputService(storage.getArtifacts(), contents),
                new ArtifactTurnInputService(storage.getArtifacts(), contents),
                lifecycle,
                contents,
                storage.getArtifacts());
    }
}
