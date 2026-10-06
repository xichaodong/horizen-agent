package dev.horizen.agent.web.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.beans.ConstructorProperties;
import java.time.Duration;

/**
 * 主模型与单次执行的配置参数；脚本模式和远端模型模式在组装时选择。
 */
@ConfigurationProperties(prefix = "horizen.agent")
@Getter
@EqualsAndHashCode
@ToString
public class AgentProperties {
    /**
     * Agent键使用的固定标识或协议文本。
     */
    public static final String AGENT_KEY = "horizen-web-agent";

    /**
     * 模型或服务访问凭据，只供服务端调用使用。
     */
    @ToString.Exclude
    private final String apiKey;

    /**
     * 远端服务的基础地址，用于拼接接口路径。
     */
    private final String baseUrl;

    /**
     * 模型服务识别的模型名称，脚本模式使用对应的演示名称。
     */
    private final String modelName;

    /**
     * 模型执行模式，用于选择远端模型或确定性脚本模型。
     */
    private final ModelMode modelMode;

    /**
     * 单次 Agent 执行允许的模型与工具循环次数上限。
     */
    private final int maxIters;

    /**
     * 单次执行流允许持续的最长时间。
     */
    private final Duration streamTimeout;

    /**
     * 执行流没有产生新事件时允许的最长空闲时间。
     */
    private final Duration idleTimeout;

    /**
     * 创建Agent配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param apiKey        模型或服务访问凭据，只供服务端调用使用。
     * @param baseUrl       远端服务的基础地址，用于拼接接口路径。
     * @param modelName     模型服务识别的模型名称，脚本模式使用对应的演示名称。
     * @param modelMode     模型执行模式，用于选择远端模型或确定性脚本模型。
     * @param maxIters      单次 Agent 执行允许的模型与工具循环次数上限。
     * @param streamTimeout 单次执行流允许持续的最长时间。
     * @param idleTimeout   执行流没有产生新事件时允许的最长空闲时间。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
            "apiKey",
            "baseUrl",
            "modelName",
            "modelMode",
            "maxIters",
            "streamTimeout",
            "idleTimeout"
    })
    public AgentProperties(
            String apiKey,
            String baseUrl,
            String modelName,
            ModelMode modelMode,
            Integer maxIters,
            Duration streamTimeout,
            Duration idleTimeout) {
        apiKey = apiKey == null ? "" : apiKey.trim();
        baseUrl = text(baseUrl, "https://ark.cn-beijing.volces.com/api/v3");
        modelName = text(modelName, "deepseek-v4-1-flash-260910");
        modelMode = modelMode == null ? ModelMode.REMOTE : modelMode;
        maxIters = maxIters == null ? 10 : maxIters;
        streamTimeout = streamTimeout == null ? Duration.ofMinutes(10) : streamTimeout;
        idleTimeout = idleTimeout == null ? Duration.ofMinutes(2) : idleTimeout;
        if (streamTimeout.isNegative() || streamTimeout.isZero()) {
            throw new IllegalArgumentException("streamTimeout must be positive");
        }
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        if (maxIters <= 0 || maxIters > 100) {
            throw new IllegalArgumentException("maxIters must be between 1 and 100");
        }

        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.modelMode = modelMode;
        this.maxIters = maxIters;
        this.streamTimeout = streamTimeout;
        this.idleTimeout = idleTimeout;
    }

    /**
     * 检查ready对应的条件，供调用方选择后续处理分支。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean ready() {
        return modelMode == ModelMode.SCRIPTED || !apiKey.isBlank();
    }

    /**
     * 脱敏Agent配置。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    public String redact(String value) {
        return apiKey.isBlank() ? value : value.replace(apiKey, "***");
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value    待校验、转换或保存的原始值。
     * @param fallback 当前Agent配置使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 主模型执行模式；SCRIPTED 为确定性演示，REMOTE 为远端模型调用。
     */
    public enum ModelMode {
        /**
         * 通过远端服务完成对应功能。
         */
        REMOTE,
        /**
         * 使用确定性脚本模型，不调用远端模型服务。
         */
        SCRIPTED
    }
}
