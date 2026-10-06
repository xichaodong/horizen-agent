package dev.horizen.agent.sandbox.e2b.http;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import okhttp3.OkHttpClient;

import java.nio.file.Path;
import java.util.Map;

/**
 * 普通 JSON/HTTP E2B 后端的 Harness 文件系统配置。
 */
public final class HttpE2bFilesystemSpec extends SandboxFilesystemSpec {
    /**
     * 可供当前请求选择的选项或策略集合。
     */
    @Getter(value = AccessLevel.PROTECTED, onMethod_ = @Override)
    @Accessors(fluent = true)
    private final HttpE2bSandboxClientOptions clientOptions = new HttpE2bSandboxClientOptions();

    /**
     * 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    @Setter
    @Accessors(fluent = true, chain = true)
    private SandboxClient<?> client;

    /**
     * 远程快照保存与恢复能力的配置。
     */
    @Getter(value = AccessLevel.PROTECTED, onMethod_ = @Override)
    @Accessors(fluent = true)
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();

    /**
     * 本次执行工作目录与文件系统能力的配置。
     */
    @Getter(value = AccessLevel.PROTECTED, onMethod_ = @Override)
    @Accessors(fluent = true)
    @Setter
    private WorkspaceSpec workspaceSpec = new WorkspaceSpec();

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec httpClient(OkHttpClient value) {
        clientOptions.setHttpClient(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec apiKey(String value) {
        clientOptions.setApiKey(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec apiBaseUrl(String value) {
        clientOptions.setApiBaseUrl(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec runtimeBaseUrlPattern(String value) {
        clientOptions.setRuntimeBaseUrlPattern(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec templateId(String value) {
        clientOptions.setTemplateId(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec workspaceRoot(String value) {
        clientOptions.setWorkspaceRoot(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec sandboxTimeoutSeconds(int value) {
        clientOptions.setSandboxTimeoutSeconds(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec connectTimeoutSeconds(int value) {
        clientOptions.setConnectTimeoutSeconds(value);
        return this;
    }

    /**
     * 读取超时秒。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec readTimeoutSeconds(int value) {
        clientOptions.setReadTimeoutSeconds(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec maxOutputBytes(int value) {
        clientOptions.setMaxOutputBytes(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec environment(Map<String, String> value) {
        clientOptions.setEnvironment(value);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec metadata(Map<String, String> value) {
        clientOptions.setMetadata(value);
        return this;
    }

    /**
     * 读取快照中的限制集合。
     *
     * @param bytes          当前操作处理的内容字节。
     * @param entries        当前HTTP2B文件系统规范使用的条目集合，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec snapshotLimits(long bytes, int entries, int timeoutSeconds) {
        clientOptions.setMaxSnapshotBytes(bytes);
        clientOptions.setMaxSnapshotEntries(entries);
        clientOptions.setSnapshotTimeoutSeconds(timeoutSeconds);
        return this;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bFilesystemSpec处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec refreshPublishedWorkspace(boolean value) {
        clientOptions.setRefreshPublishedWorkspace(value);
        return this;
    }

    /**
     * 读取快照中的规范。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec snapshotSpec(SandboxSnapshotSpec value) {
        this.snapshotSpec = value;
        super.snapshotSpec(value);
        return this;
    }

    /**
     * 创建客户端。
     *
     * @return 本次操作返回的沙箱客户端结果。
     */
    @Override
    protected SandboxClient<?> createClient() {
        validate();
        return client != null ? client : new HttpE2bSandboxClient(clientOptions, null);
    }

    /**
     * 校验当前HTTP2B文件系统规范的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void validate() {
        require(clientOptions.getApiKey(), "apiKey");
        require(clientOptions.getApiBaseUrl(), "apiBaseUrl");
        require(clientOptions.getRuntimeBaseUrlPattern(), "runtimeBaseUrlPattern");
        require(clientOptions.getTemplateId(), "templateId");
        require(clientOptions.getWorkspaceRoot(), "workspaceRoot");
        Path root = Path.of(clientOptions.getWorkspaceRoot()).normalize();
        if (!root.isAbsolute() || root.getNameCount() == 0) {
            throw new IllegalArgumentException(
                    "workspaceRoot must be an absolute non-root directory");
        }
    }

    /**
     * 取得并校验HTTP2B文件系统规范。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name  需要定位或处理的名称。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 未配置");
        }
    }
}
