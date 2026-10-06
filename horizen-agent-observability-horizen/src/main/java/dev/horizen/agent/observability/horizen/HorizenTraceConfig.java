package dev.horizen.agent.observability.horizen;

import lombok.Data;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 追踪采集与可选 HTTP 导出器共用的配置。
 */
@Data
public class HorizenTraceConfig {
    /**
     * 远端服务的基础地址，用于拼接接口路径。
     */
    private URI baseUrl;

    /**
     * 工作区或发布所属 Project 的标识，参与资源归属校验。
     */
    private long projectId;

    /**
     * 观测服务端请求使用的访问凭据，不属于模型输入。
     */
    @ToString.Exclude
    private String authToken;

    /**
     * 当前事件、内容或执行的来源，供追踪生成关系与执行层级使用。
     */
    private String source;

    /**
     * 上报数据所声明的 SDK 名称，供远端识别接入来源。
     */
    private String sdkName;

    /**
     * SDK的版本，供兼容或并发检查使用。
     */
    private String sdkVersion;

    /**
     * 观测数据中的 Agent 展示名称。
     */
    private String agentName;

    /**
     * 观测数据中的执行方展示名称。
     */
    private String executorName;

    /**
     * 运行环境的标识，供远端按环境组织观测数据。
     */
    private String environment;

    /**
     * Agent目标的标识，用于关联相应记录或执行。
     */
    private Long agentTargetId;

    /**
     * 是否采集消息与工具正文；关闭时仅保留必要的运行元数据。
     */
    private boolean captureContent;

    /**
     * 单次远端请求允许的最长等待时间。
     */
    private Duration requestTimeout;

    /**
     * 积累或上报一批数据前的刷新间隔。
     */
    private Duration flushInterval;

    /**
     * 待处理队列可容纳的项目数量上限。
     */
    private int queueCapacity;

    /**
     * 关闭时等待在途任务收敛的最长时间。
     */
    private Duration shutdownTimeout;

    /**
     * 首次请求之后允许执行的额外重试次数上限。
     */
    private int maxRetries = 2;

    /**
     * 重试初始延迟的时间配置，供等待、调度或失效判断使用。
     */
    private Duration retryInitialDelay = Duration.ofMillis(100);

    /**
     * 重试最大延迟的时间配置，供等待、调度或失效判断使用。
     */
    private Duration retryMaxDelay = Duration.ofSeconds(1);

    /**
     * 校验重试策略。
     *
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void validateRetryPolicy() {
        if (maxRetries < 0
                || maxRetries > 10
                || retryInitialDelay == null
                || retryMaxDelay == null
                || retryInitialDelay.isNegative()
                || retryMaxDelay.isNegative()
                || retryInitialDelay.compareTo(retryMaxDelay) > 0
                || retryMaxDelay.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid Horizen retry policy");
        }
    }

    /**
     * 创建HorizenTrace配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param baseUrl         远端服务的基础地址，用于拼接接口路径。
     * @param projectId       工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param authToken       当前HorizenTrace配置使用的auth令牌，供其处理与状态记录使用。
     * @param source          待解析或转换的来源对象。
     * @param sdkName         当前HorizenTrace配置使用的SDK名称，供其处理与状态记录使用。
     * @param sdkVersion      SDK的版本，供兼容或并发检查使用。
     * @param agentName       当前HorizenTrace配置使用的Agent名称，供其处理与状态记录使用。
     * @param executorName    当前HorizenTrace配置使用的执行方名称，供其处理与状态记录使用。
     * @param environment     当前HorizenTrace配置使用的环境，供其处理与状态记录使用。
     * @param agentTargetId   Agent目标的标识，用于关联相应记录或执行。
     * @param captureContent  是否采集消息与工具正文；关闭时仅保留必要的运行元数据。
     * @param requestTimeout  单次远端请求允许的最长等待时间。
     * @param flushInterval   积累或上报一批数据前的刷新间隔。
     * @param queueCapacity   待处理队列可容纳的项目数量上限。
     * @param shutdownTimeout 关闭时等待在途任务收敛的最长时间。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
            "baseUrl",
            "projectId",
            "authToken",
            "source",
            "sdkName",
            "sdkVersion",
            "agentName",
            "executorName",
            "environment",
            "agentTargetId",
            "captureContent",
            "requestTimeout",
            "flushInterval",
            "queueCapacity",
            "shutdownTimeout"
    })
    public HorizenTraceConfig(
            URI baseUrl,
            long projectId,
            String authToken,
            String source,
            String sdkName,
            String sdkVersion,
            String agentName,
            String executorName,
            String environment,
            Long agentTargetId,
            boolean captureContent,
            Duration requestTimeout,
            Duration flushInterval,
            int queueCapacity,
            Duration shutdownTimeout) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (!("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URI");
        }
        if (projectId <= 0) {
            throw new IllegalArgumentException("projectId must be positive");
        }
        source = textOr(source, "horizen-agent");
        sdkName = textOr(sdkName, "horizen-agent-java");
        sdkVersion = textOr(sdkVersion, "0.1.0-SNAPSHOT");
        agentName = textOr(agentName, "horizen-agent");
        executorName = textOr(executorName, "horizen-agent");
        environment = blankToNull(environment);
        authToken = blankToNull(authToken);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        flushInterval = flushInterval == null ? Duration.ofMillis(250) : flushInterval;
        shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(3) : shutdownTimeout;
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must not be negative");
        }
        if (flushInterval.isNegative()) {
            throw new IllegalArgumentException("flushInterval must not be negative");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }

        this.baseUrl = baseUrl;
        this.projectId = projectId;
        this.authToken = authToken;
        this.source = source;
        this.sdkName = sdkName;
        this.sdkVersion = sdkVersion;
        this.agentName = agentName;
        this.executorName = executorName;
        this.environment = environment;
        this.agentTargetId = agentTargetId;
        this.captureContent = captureContent;
        this.requestTimeout = requestTimeout;
        this.flushInterval = flushInterval;
        this.queueCapacity = queueCapacity;
        this.shutdownTimeout = shutdownTimeout;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param baseUrl   远端服务的基础地址，用于拼接接口路径。
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @return 本次操作返回的HorizenTrace配置结果。
     */
    public static HorizenTraceConfig local(URI baseUrl, long projectId) {
        return new HorizenTraceConfig(
                baseUrl,
                projectId,
                null,
                "horizen-agent",
                "horizen-agent-java",
                "0.1.0-SNAPSHOT",
                "horizen-agent",
                "horizen-agent",
                null,
                null,
                false,
                Duration.ofSeconds(3),
                Duration.ofMillis(250),
                256,
                Duration.ofSeconds(3));
    }

    /**
     * 从进程环境变量加载 Horizen 追踪配置；未启用追踪时返回空 Optional。
     */
    public static Optional<HorizenTraceConfig> fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * 从输入构造环境。
     *
     * @param environment 当前HorizenTrace配置使用的环境，供其处理与状态记录使用。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static Optional<HorizenTraceConfig> fromEnvironment(Map<String, String> environment) {
        if (!booleanValue(environment.get("HORIZEN_TRACE_ENABLED"), false)) {
            return Optional.empty();
        }
        String baseUrl = firstText(environment, "HORIZEN_TRACE_BASE_URL", "HORIZEN_BASE_URL");
        String project = firstText(environment, "HORIZEN_TRACE_PROJECT_ID", "HORIZEN_PROJECT_ID");
        if (baseUrl == null || project == null) {
            throw new IllegalArgumentException(
                    "Horizen tracing is enabled but base URL or project ID is missing");
        }
        long projectId;
        try {
            projectId = Long.parseLong(project);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Horizen project ID must be an integer", error);
        }
        String source = firstText(environment, "HORIZEN_TRACE_SOURCE");
        String agentName = firstText(environment, "HORIZEN_TRACE_AGENT_NAME", "HORIZEN_AGENT_NAME");
        String executorName =
                firstText(environment, "HORIZEN_TRACE_EXECUTOR_NAME", "HORIZEN_EXECUTOR_NAME");
        String deploymentEnvironment =
                firstText(environment, "HORIZEN_TRACE_ENV", "HORIZEN_ENV", "HORIZEN_ENVIRONMENT");
        String token = firstText(environment, "HORIZEN_TRACE_TOKEN", "HORIZEN_TOKEN");
        boolean content =
                booleanValue(
                        firstText(
                                environment,
                                "HORIZEN_TRACE_CONTENT",
                                "HORIZEN_TRACE_CONTENT_ENABLED"),
                        false);
        long flushIntervalMs =
                positiveLong(environment.get("HORIZEN_TRACE_FLUSH_INTERVAL_MS"), 1000);
        int queueCapacity =
                (int) positiveLong(environment.get("HORIZEN_TRACE_MAX_QUEUE_SIZE"), 2048);
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
                        deploymentEnvironment,
                        null,
                        content,
                        Duration.ofSeconds(3),
                        Duration.ofMillis(flushIntervalMs),
                        queueCapacity,
                        Duration.ofSeconds(3));
        config.setMaxRetries((int) positiveLong(environment.get("HORIZEN_TRACE_MAX_RETRIES"), 2));
        if ("0".equals(environment.get("HORIZEN_TRACE_MAX_RETRIES"))) config.setMaxRetries(0);
        config.setRetryInitialDelay(
                Duration.ofMillis(
                        positiveLong(
                                environment.get("HORIZEN_TRACE_RETRY_INITIAL_DELAY_MS"), 100)));
        config.setRetryMaxDelay(
                Duration.ofMillis(
                        positiveLong(environment.get("HORIZEN_TRACE_RETRY_MAX_DELAY_MS"), 1000)));
        config.validateRetryPolicy();
        return Optional.of(config);
    }

    /**
     * 生成当前操作所需的textOr文本，供调用方继续处理。
     *
     * @param value    待校验、转换或保存的原始值。
     * @param fallback 当前HorizenTrace配置使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 生成当前操作所需的blankToNull文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 生成当前操作所需的firstText文本，供调用方继续处理。
     *
     * @param environment 当前HorizenTrace配置使用的环境，供其处理与状态记录使用。
     * @param names       当前HorizenTrace配置持有的名称集合对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String firstText(Map<String, String> environment, String... names) {
        for (String name : names) {
            String value = blankToNull(environment.get(name));
            if (value != null) return value;
        }
        return null;
    }

    /**
     * 检查booleanValue对应的条件，供调用方选择后续处理分支。
     *
     * @param value    待校验、转换或保存的原始值。
     * @param fallback 回退的状态标记，用于选择当前组件的处理路径。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean booleanValue(String value, boolean fallback) {
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            case "0", "false", "no", "n", "off" -> false;
            default -> fallback;
        };
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceConfig处理步骤使用。
     *
     * @param value    待校验、转换或保存的原始值。
     * @param fallback 当前HorizenTrace配置使用的回退，供其处理与状态记录使用。
     * @return 本次操作返回的长整型结果。
     */
    private static long positiveLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
