package dev.horizen.agent.sandbox.e2b.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;

import dev.horizen.agent.common.json.JsonUtils;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import java.util.UUID;

/** AgentScope 的 JSON/HTTP E2B 沙箱客户端实现。 */
public final class HttpE2bSandboxClient implements SandboxClient<HttpE2bSandboxClientOptions> {
    /** 创建当前模型或沙箱对象时使用的默认策略配置。 */
    private final HttpE2bSandboxClientOptions defaults;

    /** 当前沙箱状态协议独立使用的 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建HTTP2B沙箱客户端，初始化该组件所需的状态、配置或依赖。
     *
     * @param defaults 当前HTTP2B沙箱客户端持有的默认值对象，供相应处理步骤使用。
     * @param objectMapper 提供对象映射器能力的依赖，具体实现由当前组件的组装方传入。
     */
    public HttpE2bSandboxClient(HttpE2bSandboxClientOptions defaults, ObjectMapper objectMapper) {
        this.defaults = defaults == null ? new HttpE2bSandboxClientOptions() : defaults;
        this.objectMapper = configureObjectMapper(objectMapper);
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bSandboxClient处理步骤使用。
     *
     * @param source 待解析或转换的来源对象。
     * @return 本次操作返回的对象映射器结果。
     */
    private static ObjectMapper configureObjectMapper(ObjectMapper source) {
        ObjectMapper mapper = (source == null ? JsonUtils.newMapper() : source).copy();
        mapper.registerModule(new HarnessSandboxJacksonModule());
        mapper.registerSubtypes(new NamedType(HttpE2bSandboxState.class, "e2b-http"));
        return mapper;
    }

    /**
     * 创建HTTP2B沙箱客户端。
     *
     * @param workspaceSpec 当前HTTP2B沙箱客户端持有的工作区规范对象，供相应处理步骤使用。
     * @param snapshotSpec 当前HTTP2B沙箱客户端持有的快照规范对象，供相应处理步骤使用。
     * @param callOptions 当前HTTP2B沙箱客户端持有的调用选项集合对象，供相应处理步骤使用。
     * @return 本次操作返回的沙箱结果。
     */
    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            HttpE2bSandboxClientOptions callOptions) {
        HttpE2bSandboxClientOptions options = merge(callOptions);
        String sessionId = UUID.randomUUID().toString();
        HttpE2bSandboxState state = new HttpE2bSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec == null ? new WorkspaceSpec() : workspaceSpec.copy());
        state.setTemplateId(options.getTemplateId());
        state.setWorkspaceRoot(options.getWorkspaceRoot());
        state.setSandboxOwned(true);
        state.setWorkspaceRootReady(false);
        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
            state.setSnapshotSpec(snapshotSpec);
        }
        return new HttpE2bSandbox(state, options, objectMapper);
    }

    /**
     * 恢复HTTP2B沙箱客户端。
     *
     * @param state 当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @return 本次操作返回的沙箱结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof HttpE2bSandboxState value)) {
            throw new IllegalArgumentException(
                    "需要 HttpE2bSandboxState，实际为 " + state.getClass().getName());
        }
        if (value.getSnapshot() instanceof NoopSandboxSnapshot) {
            // 无快照意味着上一轮远端实例和工作目录都不可恢复；直接创建新的执行环境。
            return create(value.getWorkspaceSpec(), new NoopSnapshotSpec(), merge(null));
        }
        return new HttpE2bSandbox(value, merge(null), objectMapper);
    }

    /**
     * 删除HTTP2B沙箱客户端。
     *
     * @param sandbox 当前HTTP2B沙箱客户端持有的沙箱对象，供相应处理步骤使用。
     */
    @Override
    public void delete(Sandbox sandbox) {
        // 生命周期由 SandboxManager 调用 Sandbox.shutdown() 管理，此处保持幂等空操作。
    }

    /**
     * 把当前输入编码为 JSON 文本，供协议输出或持久化保存使用。
     *
     * @param state 当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception error) {
            throw new SandboxException.SandboxConfigurationException("序列化 HTTP E2B 状态失败", error);
        }
    }

    /**
     * 将输入 JSON 解码为本方法声明的目标类型，供当前协议或持久化读取使用。
     *
     * @param json 当前HTTP2B沙箱客户端使用的JSON，供其处理与状态记录使用。
     * @return 本次操作返回的沙箱工作状态结果。
     */
    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception error) {
            throw new SandboxException.SandboxConfigurationException("反序列化 HTTP E2B 状态失败", error);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bSandboxClient处理步骤使用。
     *
     * @param json 当前HTTP2B沙箱客户端使用的JSON，供其处理与状态记录使用。
     * @param snapshotSpec 当前HTTP2B沙箱客户端持有的快照规范对象，供相应处理步骤使用。
     * @return 本次操作返回的沙箱工作状态结果。
     */
    @Override
    public SandboxState deserializeState(String json, SandboxSnapshotSpec snapshotSpec) {
        SandboxState state = deserializeState(json);
        if (state.getSnapshot() != null && snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(state.getSnapshot().getId()));
            ((HttpE2bSandboxState) state).setSnapshotSpec(snapshotSpec);
        }
        return state;
    }

    /**
     * 合并HTTP2B沙箱客户端。
     *
     * @param call 当前HTTP2B沙箱客户端持有的调用对象，供相应处理步骤使用。
     * @return 本次操作返回的HTTP2B沙箱客户端选项集合结果。
     */
    private HttpE2bSandboxClientOptions merge(HttpE2bSandboxClientOptions call) {
        HttpE2bSandboxClientOptions result = copy(defaults);
        if (call == null) {
            return result;
        }
        copyPresent(call, result);
        return result;
    }

    /**
     * 复制HTTP2B沙箱客户端。
     *
     * @param source 待解析或转换的来源对象。
     * @return 本次操作返回的HTTP2B沙箱客户端选项集合结果。
     */
    private static HttpE2bSandboxClientOptions copy(HttpE2bSandboxClientOptions source) {
        HttpE2bSandboxClientOptions target = new HttpE2bSandboxClientOptions();
        copyPresent(source, target);
        return target;
    }

    /**
     * 复制存在。
     *
     * @param source 待解析或转换的来源对象。
     * @param target 本次转换、状态更新或内容写入的目标。
     */
    private static void copyPresent(
            HttpE2bSandboxClientOptions source, HttpE2bSandboxClientOptions target) {
        if (source.getHttpClient() != null) target.setHttpClient(source.getHttpClient());
        if (source.getApiKey() != null) target.setApiKey(source.getApiKey());
        if (source.getApiBaseUrl() != null) target.setApiBaseUrl(source.getApiBaseUrl());
        if (source.getRuntimeBaseUrlPattern() != null) {
            target.setRuntimeBaseUrlPattern(source.getRuntimeBaseUrlPattern());
        }
        if (source.getTemplateId() != null) target.setTemplateId(source.getTemplateId());
        if (source.getWorkspaceRoot() != null) target.setWorkspaceRoot(source.getWorkspaceRoot());
        if (source.getSandboxTimeoutSeconds() > 0) {
            target.setSandboxTimeoutSeconds(source.getSandboxTimeoutSeconds());
        }
        if (source.getConnectTimeoutSeconds() > 0) {
            target.setConnectTimeoutSeconds(source.getConnectTimeoutSeconds());
        }
        if (source.getReadTimeoutSeconds() > 0) {
            target.setReadTimeoutSeconds(source.getReadTimeoutSeconds());
        }
        if (source.getMaxOutputBytes() > 0) target.setMaxOutputBytes(source.getMaxOutputBytes());
        target.setMaxSnapshotBytes(source.getMaxSnapshotBytes());
        target.setMaxSnapshotEntries(source.getMaxSnapshotEntries());
        target.setSnapshotTimeoutSeconds(source.getSnapshotTimeoutSeconds());
        target.setRefreshPublishedWorkspace(source.isRefreshPublishedWorkspace());
        target.setEnvironment(source.getEnvironment());
        target.setMetadata(source.getMetadata());
    }
}
