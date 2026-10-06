package dev.horizen.agent.web.bootstrap;

import dev.horizen.agent.application.artifact.ArtifactApplicationService;
import dev.horizen.agent.application.interaction.ApprovalApplicationService;
import dev.horizen.agent.application.interaction.AskUserAnswerValidator;
import dev.horizen.agent.application.interaction.AskUserApplicationService;
import dev.horizen.agent.application.interaction.AskUserTurnResumer;
import dev.horizen.agent.application.session.ConversationHistoryQueryService;
import dev.horizen.agent.application.session.SessionApplicationService;
import dev.horizen.agent.application.workspace.AgentReleaseService;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.evaluation.EvaluationSessionRegistry;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.observability.JsonlTraceSink;
import dev.horizen.agent.observability.horizen.HorizenHttpBatchExporter;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.web.api.AgentApiMapper;
import dev.horizen.agent.web.api.AgentInteractionApiService;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.AgentSessionApiService;
import dev.horizen.agent.web.api.AgentStatusService;
import dev.horizen.agent.web.api.ArtifactApiService;
import dev.horizen.agent.web.bootstrap.runtime.ArtifactSupport;
import dev.horizen.agent.web.bootstrap.storage.AgentHostResources;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.AgentWorkspaceProperties;
import dev.horizen.agent.web.config.ArtifactProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.HorizenProperties;
import dev.horizen.agent.web.config.MultimodalProperties;
import dev.horizen.agent.web.config.RuntimeStorageProperties;
import dev.horizen.agent.web.config.WorkspaceStorageProperties;
import dev.horizen.agent.web.execution.AgentSecretRedactor;
import dev.horizen.agent.web.execution.AgentTurnCoordinator;
import dev.horizen.agent.web.execution.AgentTurnRequestFactory;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 每个长期存活的 API 和应用组件均作为容器管理的 Bean。
 */
@Configuration(proxyBeanMethods = false)
public class AgentApiConfiguration {
    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param gateway 外部工具目录与调用的网关适配器。
     * @return 本次操作返回的AgentAPI映射器结果。
     */
    @Bean
    AgentApiMapper apiMapper(GatewayProperties gateway) {
        return new AgentApiMapper(gateway.mock());
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentApiConfiguration处理步骤使用。
     *
     * @param storage 当前AgentAPI组装持有的存储对象，供相应处理步骤使用。
     * @return 本次操作返回的会话应用服务结果。
     */
    @Bean
    SessionApplicationService sessionUseCases(ObjectProvider<RuntimeStorage> storage) {
        var data = storage.getIfAvailable();
        return data == null ? null : new SessionApplicationService(data.getSessionTurns());
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentApiConfiguration处理步骤使用。
     *
     * @param storage   当前AgentAPI组装持有的存储对象，供相应处理步骤使用。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @return 本次操作返回的对话历史查询服务结果。
     */
    @Bean
    ConversationHistoryQueryService historyQueries(
            ObjectProvider<RuntimeStorage> storage, ObjectProvider<ArtifactSupport> artifacts) {
        var data = storage.getIfAvailable();
        var files = artifacts.getIfAvailable();
        return data == null
                ? null
                : new ConversationHistoryQueryService(
                data.getSessionTurns(),
                data.getPresentations(),
                data.getTimeline(),
                files == null ? null : files.getArtifacts());
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentApiConfiguration处理步骤使用。
     *
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @return 本次操作返回的产物应用服务结果。
     */
    @Bean
    ArtifactApplicationService artifactUseCases(ObjectProvider<ArtifactSupport> artifacts) {
        var files = artifacts.getIfAvailable();
        return files == null
                ? null
                : new ArtifactApplicationService(
                files.getLifecycle(), files.getArtifacts(), files.getContents());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param support    当前AgentAPI组装持有的支持对象，供相应处理步骤使用。
     * @param mapper     本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param useCases   当前AgentAPI组装持有的使用用例集合对象，供相应处理步骤使用。
     * @param properties 内容存储配置，提供上传前校验的最大字节数。
     * @return 本次操作返回的产物API服务结果。
     */
    @Bean
    ArtifactApiService artifactApi(
            ObjectProvider<ArtifactSupport> support,
            AgentApiMapper mapper,
            ObjectProvider<ArtifactApplicationService> useCases,
            ArtifactProperties properties) {
        return new ArtifactApiService(
                support.getIfAvailable(),
                mapper,
                useCases.getIfAvailable(),
                properties.getMaxObjectBytes());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param runtime   执行 Agent 模型与工具循环的运行时接口。
     * @param storage   当前AgentAPI组装持有的存储对象，供相应处理步骤使用。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param events    当前执行或历史事件集合，供持久化、回放与观测使用。
     * @param mapper    本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param sessions  会话对象或会话索引，按相应的归属键定位数据。
     * @param history   当前AgentAPI组装持有的历史对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent会话API服务结果。
     */
    @Bean
    AgentSessionApiService sessionApi(
            ObjectProvider<AgentRuntime> runtime,
            ObjectProvider<RuntimeStorage> storage,
            ObjectProvider<ArtifactSupport> artifacts,
            ObjectProvider<RedisTurnEventBridge> events,
            AgentApiMapper mapper,
            ObjectProvider<SessionApplicationService> sessions,
            ObjectProvider<ConversationHistoryQueryService> history) {
        var data = storage.getIfAvailable();
        return new AgentSessionApiService(
                runtime.getIfAvailable(),
                data == null ? null : data.getSessionTurns(),
                data == null ? null : data.getPresentations(),
                data == null ? null : data.getTimeline(),
                artifacts.getIfAvailable(),
                events.getIfAvailable(),
                mapper,
                sessions.getIfAvailable(),
                history.getIfAvailable());
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentApiConfiguration处理步骤使用。
     *
     * @param artifacts   产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param multimodal  当前AgentAPI组装持有的多模态对象，供相应处理步骤使用。
     * @param gateway     外部工具目录与调用的网关适配器。
     * @param exporter    当前AgentAPI组装持有的上报器对象，供相应处理步骤使用。
     * @param mapper      本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param evaluations 当前AgentAPI组装持有的evaluations对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent执行请求工厂结果。
     */
    @Bean
    AgentTurnRequestFactory turnRequests(
            ObjectProvider<ArtifactSupport> artifacts,
            MultimodalProperties multimodal,
            GatewayProperties gateway,
            ObjectProvider<HorizenHttpBatchExporter> exporter,
            AgentApiMapper mapper,
            EvaluationSessionRegistry evaluations) {
        var factory =
                new AgentTurnRequestFactory(
                        artifacts.getIfAvailable(),
                        multimodal,
                        gateway,
                        exporter.getIfAvailable() != null,
                        mapper,
                        evaluations);
        return factory;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param agent     当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param gateway   外部工具目录与调用的网关适配器。
     * @param trace     当前AgentAPI组装持有的Trace对象，供相应处理步骤使用。
     * @param sandbox   当前AgentAPI组装持有的沙箱对象，供相应处理步骤使用。
     * @param release   当前AgentAPI组装持有的发布对象，供相应处理步骤使用。
     * @param workspace 当前AgentAPI组装持有的工作区对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent密钥脱敏器结果。
     */
    @Bean
    AgentSecretRedactor secretRedactor(
            AgentProperties agent,
            GatewayProperties gateway,
            HorizenProperties trace,
            E2bSandboxProperties sandbox,
            AgentWorkspaceProperties release,
            WorkspaceStorageProperties workspace) {
        return new AgentSecretRedactor(agent, gateway, trace, sandbox, release, workspace);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     * @return 本次操作返回的JSONLTrace上报端结果。
     */
    @Bean(destroyMethod = "close")
    @DependsOn("agentRuntime")
    JsonlTraceSink traceSink(ObjectProvider<AgentRuntime> runtime) {
        if (runtime.getIfAvailable() == null) return null;
        try {
            return new JsonlTraceSink(
                    Path.of(".agentscope", "web-workspace", "traces", "events.jsonl"));
        } catch (IOException error) {
            LoggerFactory.getLogger(AgentApiConfiguration.class)
                    .warn("Local tracing unavailable ({})", error.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param agent     当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param lifecycle 当前AgentAPI组装持有的生命周期对象，供相应处理步骤使用。
     * @param mapper    本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param sessions  会话对象或会话索引，按相应的归属键定位数据。
     * @param redactor  当前AgentAPI组装持有的脱敏器对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent执行协调器结果。
     */
    @Bean(destroyMethod = "close")
    AgentTurnCoordinator turnCoordinator(
            AgentProperties agent,
            TurnServicesLifecycle lifecycle,
            AgentApiMapper mapper,
            AgentSessionApiService sessions,
            AgentSecretRedactor redactor) {
        return new AgentTurnCoordinator(
                agent.getStreamTimeout(), lifecycle.getServices(), mapper, sessions, redactor);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentApiConfiguration处理步骤使用。
     *
     * @param storage     当前AgentAPI组装持有的存储对象，供相应处理步骤使用。
     * @param coordinator 当前AgentAPI组装持有的协调器对象，供相应处理步骤使用。
     * @param properties  宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param agent       当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次操作返回的审批应用服务结果。
     */
    @Bean
    ApprovalApplicationService approvalUseCases(
            ObjectProvider<RuntimeStorage> storage,
            AgentTurnCoordinator coordinator,
            RuntimeStorageProperties properties,
            AgentProperties agent) {
        var data = storage.getIfAvailable();
        return data == null
                ? null
                : new ApprovalApplicationService(
                data.getSessionTurns(),
                data.getApprovals(),
                coordinator::resumeApprovedTurn,
                data.getTransactions(),
                data.getInstanceId(),
                properties.getLeaseTtl(),
                agent.getStreamTimeout());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param storage     当前AgentAPI组装持有的存储对象，供相应处理步骤使用。
     * @param coordinator 当前AgentAPI组装持有的协调器对象，供相应处理步骤使用。
     * @param runtime     执行 Agent 模型与工具循环的运行时接口。
     * @param properties  宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param mapper      本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次操作返回的提问用户应用服务结果。
     */
    @Bean
    AskUserApplicationService askUserUseCases(
            ObjectProvider<RuntimeStorage> storage,
            AgentTurnCoordinator coordinator,
            ObjectProvider<AgentRuntime> runtime,
            RuntimeStorageProperties properties,
            AgentApiMapper mapper) {
        var data = storage.getIfAvailable();
        var execution = runtime.getIfAvailable();
        if (data == null || execution == null) return null;
        return new AskUserApplicationService(
                data.getSessionTurns(),
                data.getAskUsers(),
                AskUserAnswerValidator::encode,
                new AskUserTurnResumer() {
                    /**
                     * 恢复匿名实现。
                     *
                     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
                     * @param turn 当前匿名实现持有的执行对象，供相应处理步骤使用。
                     * @param request 当前操作的请求参数。
                     * @param answers 用户对澄清问题的回答集合，按问题标识关联选项和补充文本。
                     */
                    public void resume(
                            ExecutionIdentity identity,
                            AgentTurn turn,
                            AskUserRequest request,
                            String answers) {
                        coordinator.resumeAskUserTurn(identity, turn, request, answers);
                    }

                    /**
                     * 完成当前操作的timeout步骤，按实现更新相应状态或依赖。
                     *
                     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
                     * @param turn 当前匿名实现持有的执行对象，供相应处理步骤使用。
                     * @param request 当前操作的请求参数。
                     */
                    public void timeout(
                            ExecutionIdentity identity, AgentTurn turn, AskUserRequest request) {
                        execution.timeoutCurrentTurn(
                                identity.getOwnerKey(), turn.getSessionId(), turn.getTurnId());
                    }
                },
                data.getTransactions(),
                data.getInstanceId(),
                properties.getLeaseTtl());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param approvals 审批存储或待处理审批集合，用于原执行的暂停与恢复。
     * @param asks      当前AgentAPI组装持有的提问集合对象，供相应处理步骤使用。
     * @param mapper    本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次操作返回的Agent交互API服务结果。
     */
    @Bean
    AgentInteractionApiService interactionApi(
            ObjectProvider<ApprovalApplicationService> approvals,
            ObjectProvider<AskUserApplicationService> asks,
            AgentApiMapper mapper) {
        return new AgentInteractionApiService(
                approvals.getIfAvailable(), asks.getIfAvailable(), mapper);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param storage 当前AgentAPI组装持有的存储对象，供相应处理步骤使用。
     * @param events  当前执行或历史事件集合，供持久化、回放与观测使用。
     * @return 本次操作返回的Agent宿主资源集合结果。
     */
    @Bean
    AgentHostResources hostResources(
            ObjectProvider<RuntimeStorage> storage, ObjectProvider<RedisTurnEventBridge> events) {
        return new AgentHostResources(storage.getIfAvailable(), events.getIfAvailable());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param agent     当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param gateway   外部工具目录与调用的网关适配器。
     * @param trace     当前AgentAPI组装持有的Trace对象，供相应处理步骤使用。
     * @param sandbox   当前AgentAPI组装持有的沙箱对象，供相应处理步骤使用。
     * @param exporter  当前AgentAPI组装持有的上报器对象，供相应处理步骤使用。
     * @param workspace 当前AgentAPI组装持有的工作区对象，供相应处理步骤使用。
     * @param releases  当前AgentAPI组装持有的发布集合对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent状态服务结果。
     */
    @Bean
    AgentStatusService statusService(
            AgentProperties agent,
            GatewayProperties gateway,
            HorizenProperties trace,
            E2bSandboxProperties sandbox,
            ObjectProvider<HorizenHttpBatchExporter> exporter,
            AgentWorkspaceProperties workspace,
            ObjectProvider<AgentReleaseService> releases) {
        return new AgentStatusService(
                agent,
                gateway,
                trace,
                sandbox,
                exporter.getIfAvailable(),
                workspace.isEnabled(),
                releases.getIfAvailable());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param artifacts    产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param sessions     会话对象或会话索引，按相应的归属键定位数据。
     * @param interactions 提供interactions能力的依赖，具体实现由当前组件的组装方传入。
     * @param coordinator  当前AgentAPI组装持有的协调器对象，供相应处理步骤使用。
     * @param status       当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param resources    当前AgentAPI组装持有的资源集合对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent服务结果。
     */
    @Bean
    AgentService agentService(
            ArtifactApiService artifacts,
            AgentSessionApiService sessions,
            AgentInteractionApiService interactions,
            AgentTurnCoordinator coordinator,
            AgentStatusService status,
            AgentHostResources resources) {
        return new AgentService(artifacts, sessions, interactions, coordinator, status, resources);
    }
}
