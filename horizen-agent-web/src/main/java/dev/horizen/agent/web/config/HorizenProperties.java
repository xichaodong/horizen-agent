package dev.horizen.agent.web.config;

import dev.horizen.agent.observability.horizen.HorizenTraceConfig;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.time.Duration;

/** Web 宿主的可选 Horizen 追踪导出配置。 */
@ConfigurationProperties(prefix = "horizen.trace")
@Getter
@EqualsAndHashCode
public class HorizenProperties {
    /** 是否启用Horizen对应的功能。 */
    private final boolean enabled;

    /** 远端服务的基础地址，用于拼接接口路径。 */
    private final String baseUrl;

    /** 工作区或发布所属 Project 的标识，参与资源归属校验。 */
    private final Long projectId;

    /** 服务访问令牌，由宿主配置提供，用于请求认证。 */
    private final String token;

    /** 是否采集消息与工具正文；关闭时仅保留必要的运行元数据。 */
    private final boolean captureContent;

    /** 单次远端请求允许的最长等待时间。 */
    private final Duration requestTimeout;

    /** 积累或上报一批数据前的刷新间隔。 */
    private final Duration flushInterval;

    /** 待处理队列可容纳的项目数量上限。 */
    private final Integer queueCapacity;

    /** 关闭时等待在途任务收敛的最长时间。 */
    private final Duration shutdownTimeout;

    /** 当前事件、内容或执行的来源，供追踪生成关系与执行层级使用。 */
    private final String source;

    /** 观测数据中的 Agent 展示名称。 */
    private final String agentName;

    /** 观测数据中的执行方展示名称。 */
    private final String executorName;

    /** 运行环境的标识，供远端按环境组织观测数据。 */
    private final String environment;

    /** 首次请求之后允许执行的额外重试次数上限。 */
    private final Integer maxRetries;

    /** 重试初始延迟的时间配置，供等待、调度或失效判断使用。 */
    private final Duration retryInitialDelay;

    /** 重试最大延迟的时间配置，供等待、调度或失效判断使用。 */
    private final Duration retryMaxDelay;

    /**
     * 创建Horizen配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param enabled 是否启用Horizen对应的功能。
     * @param baseUrl 远端服务的基础地址，用于拼接接口路径。
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param token 服务访问令牌，由宿主配置提供，用于请求认证。
     * @param captureContent 是否采集消息与工具正文；关闭时仅保留必要的运行元数据。
     * @param requestTimeout 单次远端请求允许的最长等待时间。
     * @param flushInterval 积累或上报一批数据前的刷新间隔。
     * @param queueCapacity 待处理队列可容纳的项目数量上限。
     * @param shutdownTimeout 关闭时等待在途任务收敛的最长时间。
     */
    public HorizenProperties(
            boolean enabled,
            String baseUrl,
            Long projectId,
            String token,
            boolean captureContent,
            Duration requestTimeout,
            Duration flushInterval,
            Integer queueCapacity,
            Duration shutdownTimeout) {
        this(
                enabled,
                baseUrl,
                projectId,
                token,
                captureContent,
                requestTimeout,
                flushInterval,
                queueCapacity,
                shutdownTimeout,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 创建Horizen配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param enabled 是否启用Horizen对应的功能。
     * @param baseUrl 远端服务的基础地址，用于拼接接口路径。
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param token 服务访问令牌，由宿主配置提供，用于请求认证。
     * @param captureContent 是否采集消息与工具正文；关闭时仅保留必要的运行元数据。
     * @param requestTimeout 单次远端请求允许的最长等待时间。
     * @param flushInterval 积累或上报一批数据前的刷新间隔。
     * @param queueCapacity 待处理队列可容纳的项目数量上限。
     * @param shutdownTimeout 关闭时等待在途任务收敛的最长时间。
     * @param source 待解析或转换的来源对象。
     * @param agentName 当前Horizen配置使用的Agent名称，供其处理与状态记录使用。
     * @param executorName 当前Horizen配置使用的执行方名称，供其处理与状态记录使用。
     * @param environment 当前Horizen配置使用的环境，供其处理与状态记录使用。
     * @param maxRetries 当前Horizen配置使用的最大重试，供其处理与状态记录使用。
     * @param retryInitialDelay 重试初始延迟的时间配置，供等待、调度或失效判断使用。
     * @param retryMaxDelay 重试最大延迟的时间配置，供等待、调度或失效判断使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorBinding
    public HorizenProperties(
            boolean enabled,
            String baseUrl,
            Long projectId,
            String token,
            boolean captureContent,
            Duration requestTimeout,
            Duration flushInterval,
            Integer queueCapacity,
            Duration shutdownTimeout,
            String source,
            String agentName,
            String executorName,
            String environment,
            Integer maxRetries,
            Duration retryInitialDelay,
            Duration retryMaxDelay) {
        baseUrl = text(baseUrl);
        token = text(token);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        flushInterval = flushInterval == null ? Duration.ofSeconds(1) : flushInterval;
        queueCapacity = queueCapacity == null ? 2048 : queueCapacity;
        shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(3) : shutdownTimeout;
        if (enabled && (baseUrl.isEmpty() || projectId == null || projectId <= 0)) {
            throw new IllegalArgumentException(
                    "Horizen tracing requires horizen.trace.base-url and a positive project-id");
        }

        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.projectId = projectId;
        this.token = token;
        this.captureContent = captureContent;
        this.requestTimeout = requestTimeout;
        this.flushInterval = flushInterval;
        this.queueCapacity = queueCapacity;
        this.shutdownTimeout = shutdownTimeout;
        this.source = text(source).isEmpty() ? "horizen-agent-web" : source.trim();
        this.agentName = text(agentName).isEmpty() ? "horizen-web-agent" : agentName.trim();
        this.executorName =
                text(executorName).isEmpty() ? "horizen-agent-web" : executorName.trim();
        this.environment = text(environment);
        this.maxRetries = maxRetries == null ? 2 : maxRetries;
        this.retryInitialDelay =
                retryInitialDelay == null ? Duration.ofMillis(100) : retryInitialDelay;
        this.retryMaxDelay = retryMaxDelay == null ? Duration.ofSeconds(1) : retryMaxDelay;
    }

    /**
     * 转换为配置。
     *
     * @return 本次操作返回的HorizenTrace配置结果。
     */
    public HorizenTraceConfig toConfig() {
        if (!enabled) {
            return null;
        }
        HorizenTraceConfig config =
                new HorizenTraceConfig(
                        URI.create(baseUrl),
                        projectId,
                        token,
                        source,
                        "horizen-agent-java",
                        "0.1.0-SNAPSHOT",
                        agentName,
                        executorName,
                        environment,
                        null,
                        captureContent,
                        requestTimeout,
                        flushInterval,
                        queueCapacity,
                        shutdownTimeout);
        config.setMaxRetries(maxRetries);
        config.setRetryInitialDelay(retryInitialDelay);
        config.setRetryMaxDelay(retryMaxDelay);
        config.validateRetryPolicy();
        return config;
    }

    /**
     * 脱敏Horizen配置。
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
        return "HorizenProperties[enabled="
                + enabled
                + ", projectId="
                + projectId
                + ", captureContent="
                + captureContent
                + "]";
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
