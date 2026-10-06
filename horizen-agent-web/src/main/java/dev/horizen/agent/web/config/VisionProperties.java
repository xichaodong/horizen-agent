package dev.horizen.agent.web.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * 独立视觉模型的开关与连接配置；图片理解不复用主模型实例。
 */
@ConfigurationProperties(prefix = "horizen.agent.vision")
@Getter
@EqualsAndHashCode
@ToString
public class VisionProperties {
    /**
     * 是否启用视觉工具；关闭时不注册图片分析和浏览器视觉分析工具。
     */
    private final boolean enabled;
    /**
     * 视觉模型的独立凭据；留空时复用主模型凭据，仅供服务端调用。
     */
    @ToString.Exclude
    private final String apiKey;
    /**
     * 视觉服务的 OpenAI-compatible 基础地址；留空时复用主模型地址。
     */
    @ToString.Exclude
    private final String baseUrl;
    /**
     * 视觉模型标识；启用时必须显式提供，不回退到主模型名称。
     */
    private final String modelName;

    /**
     * 校验并保存视觉配置；默认关闭，凭据和地址可按服务商需要独立填写。
     *
     * @param enabled   是否启用独立视觉模型。
     * @param apiKey    视觉模型凭据，空值表示复用主模型凭据。
     * @param baseUrl   视觉服务地址，空值表示复用主模型地址。
     * @param modelName 启用时必填的视觉模型标识。
     */
    public VisionProperties(Boolean enabled, String apiKey, String baseUrl, String modelName) {
        this.enabled = Boolean.TRUE.equals(enabled);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.modelName = modelName == null ? "" : modelName.trim();
        if (this.enabled && this.modelName.isBlank()) {
            throw new IllegalArgumentException("horizen.agent.vision.model-name is required when vision is enabled");
        }
        if (this.enabled && !this.baseUrl.isBlank()) {
            try {
                URI address = URI.create(this.baseUrl);
                if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme()))
                        || address.getHost() == null || address.getUserInfo() != null
                        || address.getQuery() != null || address.getFragment() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("horizen.agent.vision.base-url must be an HTTP(S) base URL without credentials or query parameters");
            }
        }
    }

    /**
     * 从对外错误说明中删除视觉服务凭据。
     *
     * @param text 待输出的诊断文字，允许为 null。
     * @return 已替换视觉凭据的文字；null 输入保持 null。
     */
    public String redact(String text) {
        return text == null || apiKey.isBlank() ? text : text.replace(apiKey, "***");
    }
}
