package dev.horizen.agent.web.config;

import dev.horizen.agent.sandbox.e2b.http.HttpE2bFilesystemSpec;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.beans.ConstructorProperties;
import java.net.URI;
import java.util.Map;

/** 本地 Web 测试宿主使用的 E2B 沙箱配置。 */
@ConfigurationProperties(prefix = "horizen.agent.sandbox.e2b")
@Getter
@EqualsAndHashCode
public class E2bSandboxProperties {
    /** 是否启用2B沙箱对应的功能。 */
    private final boolean enabled;

    /** 模型或服务访问凭据，只供服务端调用使用。 */
    private final String apiKey;

    /** 远端沙箱管理 API 的基础地址。 */
    private final String apiBaseUrl;

    /** 根据沙箱标识构造运行端访问地址的模板。 */
    private final String runtimeBaseUrlPattern;

    /** 模板的标识，用于关联相应记录或执行。 */
    private final String templateId;

    /** 执行工作区的根目录，用于解析任务文件与脚本路径。 */
    private final String workspaceRoot;

    /** 沙箱超时，单位为秒。 */
    private final Integer sandboxTimeoutSeconds;

    /** 沙箱或状态隔离范围，由宿主选择并传给运行时。 */
    private final IsolationScope isolationScope;

    /** 连接超时，单位为秒。 */
    private final Integer connectTimeoutSeconds;

    /** 读取超时，单位为秒。 */
    private final Integer readTimeoutSeconds;

    /** 命令输出允许保留的最大字节数，超出时按执行器策略处理。 */
    private final Integer maxOutputBytes;

    /**
     * 创建2B沙箱配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param enabled 是否启用2B沙箱对应的功能。
     * @param apiKey 模型或服务访问凭据，只供服务端调用使用。
     * @param apiBaseUrl 当前2B沙箱配置使用的API基础URL，供其处理与状态记录使用。
     * @param runtimeBaseUrlPattern 当前2B沙箱配置使用的运行时基础URL校验模式，供其处理与状态记录使用。
     * @param templateId 模板的标识，用于关联相应记录或执行。
     * @param workspaceRoot 执行工作区的根目录，用于解析任务文件与脚本路径。
     * @param sandboxTimeoutSeconds 沙箱超时，单位为秒。
     * @param isolationScope 沙箱或状态隔离范围，由宿主选择并传给运行时。
     * @param connectTimeoutSeconds 连接超时，单位为秒。
     * @param readTimeoutSeconds 读取超时，单位为秒。
     * @param maxOutputBytes 命令输出允许保留的最大字节数，超出时按执行器策略处理。
     */
    @ConstructorProperties({
        "enabled",
        "apiKey",
        "apiBaseUrl",
        "runtimeBaseUrlPattern",
        "templateId",
        "workspaceRoot",
        "sandboxTimeoutSeconds",
        "isolationScope",
        "connectTimeoutSeconds",
        "readTimeoutSeconds",
        "maxOutputBytes"
    })
    public E2bSandboxProperties(
            boolean enabled,
            String apiKey,
            String apiBaseUrl,
            String runtimeBaseUrlPattern,
            String templateId,
            String workspaceRoot,
            Integer sandboxTimeoutSeconds,
            IsolationScope isolationScope,
            Integer connectTimeoutSeconds,
            Integer readTimeoutSeconds,
            Integer maxOutputBytes) {
        apiKey = text(apiKey, "");
        apiBaseUrl = text(apiBaseUrl, "");
        runtimeBaseUrlPattern = text(runtimeBaseUrlPattern, "");
        templateId = text(templateId, "");
        workspaceRoot = text(workspaceRoot, "/tmp/horizen-agent");
        sandboxTimeoutSeconds = positive(sandboxTimeoutSeconds, 300, "sandboxTimeoutSeconds");
        isolationScope = isolationScope == null ? IsolationScope.SESSION : isolationScope;
        connectTimeoutSeconds = positive(connectTimeoutSeconds, 30, "connectTimeoutSeconds");
        readTimeoutSeconds = positive(readTimeoutSeconds, 120, "readTimeoutSeconds");
        maxOutputBytes = positive(maxOutputBytes, 524288, "maxOutputBytes");
        if (!apiBaseUrl.isBlank()) {
            validateEndpoint(apiBaseUrl);
        }
        if (!runtimeBaseUrlPattern.isBlank()) {
            validateRuntimePattern(runtimeBaseUrlPattern);
        }
        if (enabled) {
            require(apiKey, "api-key");
            require(apiBaseUrl, "api-base-url");
            require(runtimeBaseUrlPattern, "runtime-base-url-pattern");
            require(templateId, "template-id");
        }

        this.enabled = enabled;
        this.apiKey = apiKey;
        this.apiBaseUrl = apiBaseUrl;
        this.runtimeBaseUrlPattern = runtimeBaseUrlPattern;
        this.templateId = templateId;
        this.workspaceRoot = workspaceRoot;
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
        this.isolationScope = isolationScope;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * 转换为规范。
     *
     * @return 本次操作返回的HTTP2B文件系统规范结果。
     */
    public HttpE2bFilesystemSpec toSpec() {
        HttpE2bFilesystemSpec spec =
                new HttpE2bFilesystemSpec()
                        .apiKey(apiKey)
                        .apiBaseUrl(apiBaseUrl)
                        .runtimeBaseUrlPattern(runtimeBaseUrlPattern)
                        .templateId(templateId)
                        .workspaceRoot(workspaceRoot)
                        .sandboxTimeoutSeconds(sandboxTimeoutSeconds)
                        .connectTimeoutSeconds(connectTimeoutSeconds)
                        .readTimeoutSeconds(readTimeoutSeconds)
                        .maxOutputBytes(maxOutputBytes)
                        .environment(Map.of("BROWSER_COMMANDLINE_ARGS", browserCommandLineArgs()))
                        .snapshotSpec(new NoopSnapshotSpec());
        spec.isolationScope(isolationScope);
        return spec;
    }

    /**
     * 脱敏2B沙箱配置。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    public String redact(String value) {
        return value == null || apiKey.isBlank() ? value : value.replace(apiKey, "***");
    }

    /**
     * 生成当前对象的诊断文本。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String toString() {
        return "E2bSandboxProperties[enabled="
                + enabled
                + ", apiBaseUrl="
                + apiBaseUrl
                + ", runtimeBaseUrlPattern="
                + runtimeBaseUrlPattern
                + ", templateId="
                + templateId
                + ", protocol=json-sync, isolationScope="
                + isolationScope
                + "]";
    }

    /**
     * 校验接口地址。
     *
     * @param value 待校验、转换或保存的原始值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void validateEndpoint(String value) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("E2B api-base-url 必须是 HTTP(S) 基础地址");
        }
    }

    /**
     * 校验运行时校验模式。
     *
     * @param value 待校验、转换或保存的原始值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void validateRuntimePattern(String value) {
        if (!value.contains("{sandbox_id}")) {
            throw new IllegalArgumentException("E2B runtime-base-url-pattern 必须包含 {sandbox_id}");
        }
        validateEndpoint(value.replace("{sandbox_id}", "sandbox"));
    }

    /**
     * 计算或取得本方法声明的结果，供当前E2bSandboxProperties处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param fallback 当前2B沙箱配置使用的回退，供其处理与状态记录使用。
     * @param name 需要定位或处理的名称。
     * @return 本次操作返回的整数结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static int positive(Integer value, int fallback, String name) {
        int resolved = value == null ? fallback : value;
        if (resolved <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
        return resolved;
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param fallback 当前2B沙箱配置使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 生成当前操作所需的browserCommandLineArgs文本，供调用方继续处理。
     *
     * @return 本次处理生成或读取的文本。
     */
    private static String browserCommandLineArgs() {
        return "--disable-dev-shm-usage --disable-gpu --no-first-run "
                + "--no-default-browser-check --remote-allow-origins=* "
                + "--remote-debugging-port=9222 --start-maximized "
                + "--load-extension=/opt/extensions/web-rpa --extensions-on-chrome-urls";
    }

    /**
     * 取得并校验2B沙箱配置。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name 需要定位或处理的名称。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("启用 E2B 沙箱时必须配置 " + name);
        }
    }
}
