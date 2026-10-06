package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.adapter.agentscope.runtime.MultimodalInputMiddleware;
import dev.horizen.agent.adapter.agentscope.runtime.SubagentInteractionMiddleware;
import dev.horizen.agent.adapter.agentscope.runtime.SubagentResultForwardingMiddleware;
import dev.horizen.agent.adapter.agentscope.workspace.filesystem.SessionWorkspaceVolume;
import dev.horizen.agent.adapter.agentscope.workspace.snapshot.DurableWorkspaceSandboxContext;
import dev.horizen.agent.adapter.agentscope.workspace.snapshot.RepositorySnapshotSpec;
import dev.horizen.agent.adapter.skill.horizen.AgentScopeSkillRepositoryAdapter;
import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.context.ConfiguredCompactionModel;
import dev.horizen.agent.context.ContextCompactionTelemetry;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.evaluation.EvaluationModelMiddleware;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.observability.horizen.HorizenHttpBatchExporter;
import dev.horizen.agent.observability.horizen.HorizenObservedCompactionModel;
import dev.horizen.agent.observability.horizen.HorizenTraceConfig;
import dev.horizen.agent.observability.horizen.HorizenTracingMiddleware;
import dev.horizen.agent.provider.spi.gateway.GatewayBackend;
import dev.horizen.agent.provider.spi.gateway.GatewayCallerAttributes;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxState;
import dev.horizen.agent.tool.adapter.ToolCatalogResolver;
import dev.horizen.agent.tool.adapter.ToolGroupDefinition;
import dev.horizen.agent.tool.governance.ToolDescriptorRegistry;
import dev.horizen.agent.tool.governance.ToolViewMiddleware;
import dev.horizen.agent.tools.memory.CloudMemoryRecallTools;
import dev.horizen.agent.web.bootstrap.model.AgentModelFactory;
import dev.horizen.agent.web.bootstrap.model.ScriptedWebModel;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.ContextProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.MultimodalProperties;
import dev.horizen.agent.web.config.SandboxSnapshotProperties;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tool.AgentSpawnTool;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * AgentScope、模型、工具和基础设施适配器的组装工厂。
 */
public final class AgentRuntimeFactory {
    /**
     * Agent键的固定取值，用于相应策略和边界判断。
     */
    public static final String AGENT_KEY = AgentProperties.AGENT_KEY;

    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private AgentRuntimeFactory() {
    }

    /**
     * 创建Agent运行时工厂。
     *
     * @param assembly 当前Agent运行时工厂持有的组装对象，供相应处理步骤使用。
     * @return 本次操作返回的HarnessAgent运行时结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static HarnessAgentRuntime create(RuntimeAssembly assembly) {
        AgentProperties properties = assembly.getProperties();
        ContextProperties contextProperties = assembly.getContextProperties();
        GatewayProperties gatewayProperties = assembly.getGatewayProperties();
        E2bSandboxProperties sandboxProperties = assembly.getSandboxProperties();
        MultimodalProperties multimodalProperties = assembly.getMultimodalProperties();
        HorizenTraceConfig traceConfig = assembly.getTraceConfig();
        HorizenHttpBatchExporter horizenExporter = assembly.getHorizenExporter();
        AgentScopeSkillRepositoryAdapter skillRepository = assembly.getSkillRepository();
        DistributedStore distributedStore = assembly.getDistributedStore();
        SessionTurnStore sessionTurns = assembly.getSessionTurns();
        ArtifactSupport artifactSupport = assembly.getArtifactSupport();
        AskUserStore askUsers = assembly.getAskUsers();
        WorkspaceDocumentRepository workspaceDocuments = assembly.getWorkspaceDocuments();
        SandboxSnapshotSpec snapshots = assembly.getSnapshots();
        SandboxSnapshotProperties snapshotProperties = assembly.getSnapshotProperties();
        WorkspaceSnapshotPointerRepository snapshotPointers = assembly.getSnapshotPointers();
        Path publishedWorkspace = assembly.getPublishedWorkspace();
        RuntimeInfrastructure infrastructure = assembly.getInfrastructure();
        if (sandboxProperties.isEnabled()
                && (infrastructure == null || infrastructure.getSandboxClient() == null))
            throw new IllegalArgumentException(
                    "Sandbox execution requires an explicitly owned HTTP client");

        Path workspaceRoot =
                publishedWorkspace == null
                        ? Path.of(".agentscope", "web-workspace")
                        : publishedWorkspace;
        ChatModelBase model =
                infrastructure == null
                        ? AgentModelFactory.primary(properties, contextProperties)
                        : AgentModelFactory.primary(
                        properties, contextProperties, infrastructure.getModelTransport());
        Model compactionModel =
                new ConfiguredCompactionModel(
                        infrastructure == null
                                ? AgentModelFactory.compaction(properties, contextProperties, model)
                                : AgentModelFactory.compaction(
                                properties,
                                contextProperties,
                                model,
                                infrastructure.getModelTransport()),
                        contextProperties.getCompressionMaxOutputTokens(),
                        contextProperties.getCompressionTemperature(),
                        contextProperties.getCompressionTimeout(),
                        contextProperties.getCompressionMaxAttempts());
        if (traceConfig != null && horizenExporter != null) {
            compactionModel = new HorizenObservedCompactionModel(compactionModel);
        }

        Toolkit toolkit = new Toolkit();
        ToolDescriptorRegistry descriptors = new ToolDescriptorRegistry();
        AgentToolRegistry.createToolGroups(toolkit);
        if (properties.getModelMode() == AgentProperties.ModelMode.SCRIPTED) {
            toolkit.registerAgentTool(new ScriptedWebModel.ApprovalTool());
        }
        GatewayBackend gateway =
                infrastructure == null
                        ? AgentToolRegistry.createGateway(gatewayProperties)
                        : infrastructure.getGateway();
        ToolCatalogResolver catalogResolver =
                gateway == null
                        ? null
                        : AgentToolRegistry.registerGatewayTools(
                        toolkit, gateway, gatewayProperties, descriptors);

        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(AGENT_KEY)
                        .sysPrompt(
                                systemPrompt(gatewayProperties)
                                        + (workspaceDocuments == null
                                        ? ""
                                        : "\n" + CloudMemoryRecallTools.POLICY))
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(properties.getMaxIters())
                        .enableTaskList()
                        .workspace(workspaceRoot);
        if (publishedWorkspace == null)
            builder.subagent(
                    SubagentDeclaration.builder()
                            .name("general_worker")
                            .description("处理可独立拆分的调研、检索、文件分析或工具调用子任务，并返回简洁结论。")
                            .inlineAgentsBody(
                                    "你是一个受委托的通用工作者。只完成分配的子任务，"
                                            + "基于工具结果给出可核验的简洁结论；不要越过父 Agent 的权限边界。")
                            .steps(8)
                            .build());
        if (artifactSupport != null) {
            builder.artifactDeliveryTarget(artifactSupport.getDeliveryTarget());
        }
        builder.middleware(new EvaluationModelMiddleware());
        builder.middleware(ContextCompactionTelemetry.beforeCompaction());
        builder.middleware(new SubagentResultForwardingMiddleware());
        builder.middleware(
                new ToolViewMiddleware(
                        descriptors,
                        catalogResolver == null ? List.of() : List.of(catalogResolver)));
        builder.middleware(
                ContextCompactionTelemetry.afterCompaction(
                        contextProperties.isAbortOnSummaryFailure()));
        new ContextRuntimeConfigurer(contextProperties).apply(builder, model, compactionModel);
        builder.middleware(new MultimodalInputMiddleware());
        configureScriptedRuntime(properties, builder);
        WorkspaceRuntimeConfigurer.configureDistributedWorkspace(
                builder, distributedStore, sessionTurns, sandboxProperties, workspaceDocuments);
        if (traceConfig != null && horizenExporter != null) {
            builder.middleware(new HorizenTracingMiddleware(traceConfig, horizenExporter));
        }
        if (skillRepository != null) {
            builder.skillRepository(skillRepository);
        }
        SandboxContext sandboxContext =
                WorkspaceRuntimeConfigurer.configureSandbox(
                        builder,
                        distributedStore,
                        sandboxProperties,
                        workspaceDocuments,
                        snapshots,
                        snapshotProperties,
                        workspaceRoot,
                        publishedWorkspace != null,
                        infrastructure == null ? null : infrastructure.getSandboxClient());

        var childTools =
                PublishedWorkspaceConfigurer.configure(
                        builder, publishedWorkspace, sandboxProperties, toolkit);

        var questionTool = new AtomicReference<AgentTool>();
        builder.middleware(new SubagentInteractionMiddleware(questionTool::get, childTools));

        HarnessAgent agent = builder.build();
        AgentToolRegistry.registerRuntimeTools(
                agent,
                model,
                sessionTurns,
                artifactSupport,
                askUsers,
                sandboxProperties,
                multimodalProperties,
                descriptors,
                infrastructure == null
                        ? (workspaceDocuments == null
                        ? null
                        : new CloudMemoryService(workspaceDocuments))
                        : infrastructure.getMemory(),
                infrastructure == null ? null : infrastructure.getWebExtractClient());
        questionTool.set(agent.getToolkit().getTool("ask_user"));
        Consumer<RuntimeContext> prepare =
                context -> {
                    if (sandboxContext != null) context.put(SandboxContext.class, sandboxContext);
                };
        if (sandboxContext != null && snapshots != null && snapshotPointers != null) {
            DurableWorkspaceSandboxContext recovery =
                    new DurableWorkspaceSandboxContext(
                            AGENT_KEY,
                            sandboxContext,
                            snapshotPointers,
                            distributedStore.sandboxExecutionGuard(),
                            id -> {
                                @SuppressWarnings("unchecked")
                                SandboxClient<SandboxClientOptions> client =
                                        (SandboxClient<SandboxClientOptions>)
                                                sandboxContext.getClient();
                                var state =
                                        client.create(
                                                        sandboxContext.getWorkspaceSpec(),
                                                        sandboxContext.getSnapshotSpec(),
                                                        sandboxContext.getClientOptions())
                                                .getState();
                                if (id != null) {
                                    state.setSnapshot(sandboxContext.getSnapshotSpec().build(id));
                                    ((HttpE2bSandboxState) state).setSnapshotCommitted(true);
                                }
                                return state;
                            });
            prepare = recovery::prepare;
        }
        if (!sandboxProperties.isEnabled()
                && distributedStore != null
                && snapshotPointers != null) {
            var archiveContext =
                    snapshots instanceof RepositorySnapshotSpec spec ? spec.getRepository() : null;
            if (archiveContext == null)
                throw new IllegalStateException(
                        "Cloud Session files require a snapshot repository");
            prepare =
                    call ->
                            SessionWorkspaceVolume.prepare(
                                    call,
                                    AGENT_KEY,
                                    archiveContext,
                                    snapshotPointers,
                                    distributedStore.sandboxExecutionGuard(),
                                    snapshotProperties.getMaxArchiveBytes(),
                                    snapshotProperties.getMaxEntries());
        }
        if (publishedWorkspace != null) {
            Consumer<RuntimeContext> sandboxPrepare = prepare;
            prepare =
                    context -> {
                        context.put(AgentSpawnTool.CTX_FORCE_SYNC, true);
                        context.put(
                                AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS,
                                (int) Math.min(120, properties.getStreamTimeout().toSeconds()));
                        sandboxPrepare.accept(context);
                    };
        }
        Consumer<RuntimeContext> prepareSandbox = prepare;
        prepare =
                context -> {
                    if (context.get(GatewayCallerAttributes.class) == null) {
                        context.put(
                                GatewayCallerAttributes.class,
                                new GatewayCallerAttributes(
                                        gatewayProperties.getCallerAttributes()));
                    }
                    prepareSandbox.accept(context);
                };
        return new HarnessAgentRuntime(agent, prepare, snapshots == null ? null : snapshotPointers);
    }

    /**
     * 生成当前操作所需的systemPrompt文本，供调用方继续处理。
     *
     * @param gateway 外部工具目录与调用的网关适配器。
     * @return 本次处理生成或读取的文本。
     */
    private static String systemPrompt(GatewayProperties gateway) {
        return "你是一个可靠、简洁的中文助手。回答用户问题，并在需要时使用工作区能力。"
                + "工具调用超时或通信异常不代表业务没有执行，结果未确认时不重复写入，必要时请用户核实。"
                + (gateway.mock()
                ? "当前是本地 Mock 演示模式，工具返回固定合成样本，未查询真实店铺或业务系统。"
                + "必须先加载匹配的 skill，再调用与任务匹配的已声明业务工具读取演示数据。"
                + "以工具声明的演示对象、可用日期和返回数据为准，不将样本冒充用户店铺的真实情况。"
                + "若用户未指定对象，可明确采用工具目录中给出的演示对象。"
                + "未提供 ask_user 能力时用文字澄清替代；需要答复时结束本轮等用户回答。"
                + "报告直接输出在对话中；不编造链接、图像证据或成功执行业务动作，不请求真实网关。"
                : gateway.configured()
                ? "业务能力由已声明的外部工具提供。若缺少 Provider 授权，明确说明配置缺失，"
                + "不要用本地脚本或自行发送网络请求替代网关。"
                : "");
    }

    /**
     * 完成当前操作的configureScriptedRuntime步骤，按实现更新相应状态或依赖。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param builder    当前Agent运行时工厂持有的构造器对象，供相应处理步骤使用。
     */
    private static void configureScriptedRuntime(
            AgentProperties properties, HarnessAgent.Builder builder) {
        if (properties.getModelMode() != AgentProperties.ModelMode.SCRIPTED) return;
        builder.disableMemoryHooks();
        builder.permissionContext(
                PermissionContextState.builder()
                        .addAllowRule(
                                "ask_user",
                                new PermissionRule(
                                        "ask_user",
                                        null,
                                        PermissionBehavior.ALLOW,
                                        "runtime-interaction"))
                        .addAllowRule(
                                "todo_write",
                                new PermissionRule(
                                        "todo_write",
                                        null,
                                        PermissionBehavior.ALLOW,
                                        "runtime-progress"))
                        .addAskRule(
                                "scripted_dangerous_action",
                                new PermissionRule(
                                        "scripted_dangerous_action",
                                        null,
                                        PermissionBehavior.ASK,
                                        "scripted-test"))
                        .build());
    }

    /**
     * 创建网关。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的网关后端结果。
     */
    public static GatewayBackend createGateway(GatewayProperties properties) {
        return AgentToolRegistry.createGateway(properties);
    }

    /**
     * 创建工具分组集合。
     *
     * @param toolkit 当前Agent运行时工厂持有的工具集对象，供相应处理步骤使用。
     */
    public static void createToolGroups(Toolkit toolkit) {
        AgentToolRegistry.createToolGroups(toolkit);
    }

    /**
     * 确保提供方工具分组。
     *
     * @param toolkit 当前Agent运行时工厂持有的工具集对象，供相应处理步骤使用。
     * @param group   当前Agent运行时工厂持有的分组对象，供相应处理步骤使用。
     */
    public static void ensureProviderToolGroup(Toolkit toolkit, ToolGroupDefinition group) {
        AgentToolRegistry.ensureProviderToolGroup(toolkit, group);
    }
}
