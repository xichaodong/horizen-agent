package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.adapter.agentscope.artifact.ArtifactInputTool;
import dev.horizen.agent.adapter.agentscope.askuser.AskUserTool;
import dev.horizen.agent.adapter.gateway.fixture.FixtureGateway;
import dev.horizen.agent.adapter.gateway.http.GatewayClient;
import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.evaluation.EvaluationToolProvider;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.provider.spi.gateway.GatewayBackend;
import dev.horizen.agent.tool.adapter.ToolAdapterContext;
import dev.horizen.agent.tool.adapter.ToolAdapterTools;
import dev.horizen.agent.tool.adapter.ToolCatalogResolver;
import dev.horizen.agent.tool.adapter.ToolDefinition;
import dev.horizen.agent.tool.adapter.ToolGroupDefinition;
import dev.horizen.agent.tool.adapter.ToolProvider;
import dev.horizen.agent.tool.gateway.GatewayToolAdapter;
import dev.horizen.agent.tool.governance.ToolDescriptor;
import dev.horizen.agent.tool.governance.ToolDescriptorRegistry;
import dev.horizen.agent.tools.browser.SandboxBrowserTool;
import dev.horizen.agent.tools.browser.SandboxBrowserVisionTool;
import dev.horizen.agent.tools.files.SandboxPatchTool;
import dev.horizen.agent.tools.files.SandboxSearchFilesTool;
import dev.horizen.agent.tools.memory.CloudMemoryRecallTools;
import dev.horizen.agent.tools.memory.CloudMemorySaveTool;
import dev.horizen.agent.tools.memory.MemoryManageTool;
import dev.horizen.agent.tools.process.SandboxProcessTool;
import dev.horizen.agent.tools.session.CloudSessionSearchTool;
import dev.horizen.agent.tools.vision.VisionAnalyzeTool;
import dev.horizen.agent.tools.web.WebExtractTool;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.MultimodalProperties;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.tool.SkillToolGroup;
import io.agentscope.core.tool.ToolGroupScope;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

/** 注册宿主、Provider 和沙箱工具，不负责构建 Agent。 */
public final class AgentToolRegistry {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private AgentToolRegistry() {}

    /**
     * 注册运行时工具集合。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param model 当前Agent工具注册表持有的模型对象，供相应处理步骤使用。
     * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
     * @param artifactSupport 当前Agent工具注册表持有的产物支持对象，供相应处理步骤使用。
     * @param askUsers 澄清请求的存储或服务，用于回答处理与执行恢复。
     * @param sandbox 当前Agent工具注册表持有的沙箱对象，供相应处理步骤使用。
     * @param multimodal 当前Agent工具注册表持有的多模态对象，供相应处理步骤使用。
     * @param descriptors 当前Agent工具注册表持有的描述集合对象，供相应处理步骤使用。
     * @param cloudMemory 提供云端记忆能力的依赖，具体实现由当前组件的组装方传入。
     */
    public static void registerRuntimeTools(
            HarnessAgent agent,
            ChatModelBase model,
            SessionTurnStore sessions,
            ArtifactSupport artifactSupport,
            AskUserStore askUsers,
            E2bSandboxProperties sandbox,
            MultimodalProperties multimodal,
            ToolDescriptorRegistry descriptors,
            CloudMemoryService cloudMemory) {
        registerRuntimeTools(
                agent,
                model,
                sessions,
                artifactSupport,
                askUsers,
                sandbox,
                multimodal,
                descriptors,
                cloudMemory,
                null);
    }

    /**
     * 注册运行时工具集合。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param model 当前Agent工具注册表持有的模型对象，供相应处理步骤使用。
     * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
     * @param artifactSupport 当前Agent工具注册表持有的产物支持对象，供相应处理步骤使用。
     * @param askUsers 澄清请求的存储或服务，用于回答处理与执行恢复。
     * @param sandbox 当前Agent工具注册表持有的沙箱对象，供相应处理步骤使用。
     * @param multimodal 当前Agent工具注册表持有的多模态对象，供相应处理步骤使用。
     * @param descriptors 当前Agent工具注册表持有的描述集合对象，供相应处理步骤使用。
     * @param cloudMemory 提供云端记忆能力的依赖，具体实现由当前组件的组装方传入。
     * @param webClient 提供Web客户端能力的依赖，具体实现由当前组件的组装方传入。
     */
    public static void registerRuntimeTools(
            HarnessAgent agent,
            ChatModelBase model,
            SessionTurnStore sessions,
            ArtifactSupport artifactSupport,
            AskUserStore askUsers,
            E2bSandboxProperties sandbox,
            MultimodalProperties multimodal,
            ToolDescriptorRegistry descriptors,
            CloudMemoryService cloudMemory,
            HttpClient webClient) {
        String tavilyApiKey = System.getenv("TAVILY_API_KEY");
        if (tavilyApiKey == null || tavilyApiKey.isBlank())
            agent.getToolkit().removeTool("web_search");
        agent.getToolkit()
                .registerAgentTool(
                        new MemoryManageTool(
                                agent.getWorkspaceManager(),
                                cloudMemory,
                                AgentRuntimeFactory.AGENT_KEY));
        if (cloudMemory != null) {
            agent.getToolkit()
                    .registerAgentTool(
                            new CloudMemorySaveTool(cloudMemory, AgentRuntimeFactory.AGENT_KEY));
            CloudMemoryRecallTools.create(cloudMemory, AgentRuntimeFactory.AGENT_KEY)
                    .forEach(agent.getToolkit()::registerAgentTool);
        }
        if (artifactSupport != null) {
            agent.getToolkit()
                    .registerAgentTool(new ArtifactInputTool(artifactSupport.getInputs()));
        }
        agent.getToolkit()
                .registerAgentTool(
                        webClient == null
                                ? new WebExtractTool(
                                        artifactSupport == null
                                                ? null
                                                : artifactSupport.getLifecycle())
                                : new WebExtractTool(
                                        webClient,
                                        artifactSupport == null
                                                ? null
                                                : artifactSupport.getLifecycle()));
        if (artifactSupport != null) {
            VisionAnalyzeTool vision =
                    new VisionAnalyzeTool(
                            model,
                            artifactSupport.getArtifacts(),
                            artifactSupport.getContents(),
                            multimodal.getImageUrlExpiresSeconds());
            agent.getToolkit().registerAgentTool(vision);
            SandboxBrowserTool screenshot =
                    SandboxBrowserTool.createAll(artifactSupport.getLifecycle()).stream()
                            .filter(tool -> "browser_screenshot".equals(tool.getName()))
                            .findFirst()
                            .orElseThrow();
            agent.getToolkit().registerAgentTool(new SandboxBrowserVisionTool(screenshot, vision));
        }
        if (sessions != null) {
            agent.getToolkit().removeTool("session_search");
            agent.getToolkit()
                    .registerAgentTool(
                            new CloudSessionSearchTool(
                                    sessions,
                                    artifactSupport == null
                                            ? null
                                            : artifactSupport.getArtifacts()));
        }
        if (sandbox.isEnabled()) {
            SandboxBrowserTool.createAll(
                            artifactSupport == null ? null : artifactSupport.getLifecycle())
                    .stream()
                    .filter(tool -> !"browser_dialog".equals(tool.getName()))
                    .forEach(agent.getToolkit()::registerAgentTool);
            agent.getToolkit().registerAgentTool(new SandboxPatchTool());
            agent.getToolkit().registerAgentTool(new SandboxSearchFilesTool());
            agent.getToolkit().registerAgentTool(new SandboxProcessTool());
        }
        if (askUsers != null) agent.getToolkit().registerAgentTool(new AskUserTool(askUsers));
        NativeToolCatalog.register(agent.getToolkit(), descriptors);
    }

    /**
     * 创建网关。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的网关后端结果。
     */
    public static GatewayBackend createGateway(GatewayProperties properties) {
        if (properties.mock()) return new FixtureGateway(properties.getFixtureFile());
        return properties.configured()
                ? new GatewayClient(
                        URI.create(properties.getUrl()),
                        properties.getToken(),
                        properties.getTimeout(),
                        properties.getAllowedTools())
                : null;
    }

    /**
     * 注册网关工具集合。
     *
     * @param toolkit 当前Agent工具注册表持有的工具集对象，供相应处理步骤使用。
     * @param gateway 外部工具目录与调用的网关适配器。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param descriptors 当前Agent工具注册表持有的描述集合对象，供相应处理步骤使用。
     * @return 本次操作返回的工具目录解析器结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static ToolCatalogResolver registerGatewayTools(
            Toolkit toolkit,
            GatewayBackend gateway,
            GatewayProperties properties,
            ToolDescriptorRegistry descriptors) {
        ToolProvider adapter = new EvaluationToolProvider(new GatewayToolAdapter(gateway));
        try {
            List<ToolDefinition> definitions =
                    adapter.list(ToolAdapterContext.from(RuntimeContext.builder().build()))
                            .block(properties.getTimeout());
            definitions.forEach(
                    definition -> ensureProviderToolGroup(toolkit, definition.getGroup()));
            ToolCatalogResolver resolver = ToolAdapterTools.register(toolkit, adapter, definitions);
            definitions.forEach(
                    definition -> {
                        toolkit.addToolToGroup(definition.getGroup().getId(), definition.getName());
                        descriptors.register(ToolDescriptor.fromDefinition(definition));
                    });
            return resolver;
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "Unable to load the Provider tool catalog at startup", error);
        }
    }

    /**
     * 创建工具分组集合。
     *
     * @param toolkit 当前Agent工具注册表持有的工具集对象，供相应处理步骤使用。
     */
    public static void createToolGroups(Toolkit toolkit) {
        NativeToolCatalog.createGroups(toolkit);
    }

    /**
     * 确保提供方工具分组。
     *
     * @param toolkit 当前Agent工具注册表持有的工具集对象，供相应处理步骤使用。
     * @param group 当前Agent工具注册表持有的分组对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void ensureProviderToolGroup(Toolkit toolkit, ToolGroupDefinition group) {
        var existing = toolkit.getToolGroup(group.getId());
        if (existing != null) {
            if (ToolGroupDefinition.DEFAULT_GROUP_ID.equals(group.getId())) return;
            if (NativeToolCatalog.groupIds().contains(group.getId())) {
                throw new IllegalArgumentException(
                        "Provider tool group conflicts with host group: " + group.getId());
            }
            boolean sameSkill =
                    group.getActivateOnSkill().isEmpty()
                            ? !(existing instanceof SkillToolGroup)
                            : existing instanceof SkillToolGroup skill
                                    && group.getActivateOnSkill()
                                            .equals(skill.getActivateOnSkill());
            if (!sameSkill || existing.isActive() != group.isActiveByDefault()) {
                throw new IllegalArgumentException(
                        "Provider returned inconsistent tool group metadata: " + group.getId());
            }
            return;
        }
        if (group.getActivateOnSkill().isEmpty()) {
            toolkit.createToolGroup(
                    group.getId(),
                    group.getDescription(),
                    group.isActiveByDefault(),
                    ToolGroupScope.EXTERNAL);
        } else {
            toolkit.createSkillToolGroup(
                    group.getId(), group.getDescription(), false, group.getActivateOnSkill());
        }
    }
}
