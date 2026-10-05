package dev.horizen.agent.web.config;

import dev.horizen.agent.provider.spi.gateway.GatewayCallerAttributes;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.beans.ConstructorProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** 本地单用户测试应用的服务端网关配置。 */
@ConfigurationProperties(prefix = "horizen.agent.gateway")
@Getter
@EqualsAndHashCode
public class GatewayProperties {
    /** 资源或远端接口地址；具体访问范围由所属服务的配置校验。 */
    private final String url;

    /** 服务访问令牌，由宿主配置提供，用于请求认证。 */
    private final String token;

    /** 超时的时间配置，供等待、调度或失效判断使用。 */
    private final Duration timeout;

    /** 宿主允许调用的工具名称集合，供目录过滤与执行治理使用。 */
    private final Set<String> allowedTools;

    /** 当前功能模式，控制所选适配器或处理策略。 */
    private final Mode mode;

    /** 本地模拟网关的工具目录与结果样本文件。 */
    private final Path fixtureFile;

    /** 可信宿主提供的调用属性，不从模型输入中推断业务身份。 */
    private final Map<String, String> callerAttributes;

    /** 工具网关模式，区分远端 Provider 与本地固定样本。 */
    public enum Mode {
        /** 通过远端服务完成对应功能。 */
        REMOTE,
        /** 使用本地固定样本，不执行真实业务调用。 */
        MOCK
    }

    /**
     * 创建网关配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param url 资源或远端接口地址；具体访问范围由所属服务的配置校验。
     * @param token 服务访问令牌，由宿主配置提供，用于请求认证。
     * @param timeout 本次等待允许持续的最长时间。
     * @param allowedTools 宿主允许调用的工具名称集合，供目录过滤与执行治理使用。
     * @param mode 当前功能模式，控制所选适配器或处理策略。
     * @param fixtureFile 当前网关配置持有的样本文件对象，供相应处理步骤使用。
     * @param callerAttributes 可信宿主提供的调用属性，不从模型输入中推断业务身份。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorBinding
    @ConstructorProperties({
        "url",
        "token",
        "timeout",
        "allowedTools",
        "mode",
        "fixtureFile",
        "callerAttributes"
    })
    public GatewayProperties(
            String url,
            String token,
            Duration timeout,
            Set<String> allowedTools,
            Mode mode,
            Path fixtureFile,
            Map<String, String> callerAttributes) {
        mode = mode == null ? Mode.REMOTE : mode;
        fixtureFile =
                fixtureFile == null
                        ? Path.of(".agentscope", "web-workspace", "mock-gateway.json")
                        : fixtureFile;
        url = normalize(url);
        token = normalize(token);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Gateway timeout must be positive");
        }
        if (mode == Mode.REMOTE && !url.isEmpty()) {
            URI parsed = URI.create(url);
            if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null) {
                throw new IllegalArgumentException("Gateway URL must be an HTTP(S) base URL");
            }
        }

        this.url = url;
        this.token = token;
        this.timeout = timeout;
        this.allowedTools = allowedTools;
        this.mode = mode;
        this.fixtureFile = fixtureFile;
        this.callerAttributes = new GatewayCallerAttributes(callerAttributes).getValues();
    }

    /**
     * 创建网关配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param url 资源或远端接口地址；具体访问范围由所属服务的配置校验。
     * @param token 服务访问令牌，由宿主配置提供，用于请求认证。
     * @param timeout 本次等待允许持续的最长时间。
     * @param allowedTools 宿主允许调用的工具名称集合，供目录过滤与执行治理使用。
     * @param mode 当前功能模式，控制所选适配器或处理策略。
     * @param fixtureFile 当前网关配置持有的样本文件对象，供相应处理步骤使用。
     */
    public GatewayProperties(
            String url,
            String token,
            Duration timeout,
            Set<String> allowedTools,
            Mode mode,
            Path fixtureFile) {
        this(url, token, timeout, allowedTools, mode, fixtureFile, Map.of());
    }

    /**
     * 创建网关配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param url 资源或远端接口地址；具体访问范围由所属服务的配置校验。
     * @param token 服务访问令牌，由宿主配置提供，用于请求认证。
     * @param timeout 本次等待允许持续的最长时间。
     * @param allowedTools 宿主允许调用的工具名称集合，供目录过滤与执行治理使用。
     */
    public GatewayProperties(String url, String token, Duration timeout, Set<String> allowedTools) {
        this(url, token, timeout, allowedTools, Mode.REMOTE, null);
    }

    /**
     * 检查mock对应的条件，供调用方选择后续处理分支。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean mock() {
        return mode == Mode.MOCK;
    }

    /**
     * 检查configured对应的条件，供调用方选择后续处理分支。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean configured() {
        return mock() || (!url.isEmpty() && !token.isEmpty());
    }

    /**
     * 脱敏网关配置。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    public String redact(String value) {
        return value == null || token.isEmpty() ? value : value.replace(token, "***");
    }

    /**
     * 生成当前对象的诊断文本。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String toString() {
        return "GatewayProperties[mode=" + mode + ", configured=" + configured() + "]";
    }

    /**
     * 规范化网关配置。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
