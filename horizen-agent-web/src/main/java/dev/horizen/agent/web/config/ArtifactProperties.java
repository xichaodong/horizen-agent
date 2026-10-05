package dev.horizen.agent.web.config;

import dev.horizen.agent.storage.bos.BosArtifactContentStoreConfig;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Web 宿主的可选 BOS Artifact 内容存储配置。 */
@ConfigurationProperties(prefix = "horizen.agent.artifact.bos")
@Getter
@EqualsAndHashCode
@ToString
public class ArtifactProperties {
    /** 是否启用产物对应的功能。 */
    private final boolean enabled;

    /** 已解析的服务接口地址，供实际网络请求使用。 */
    private final String endpoint;

    /** 对象存储桶名称，限定内容对象的存储位置。 */
    private final String bucket;

    /** 对象存储访问标识，与 secretKey 配合完成服务端认证。 */
    @ToString.Exclude private final String accessKey;

    /** 对象存储访问密钥，不属于客户端展示数据。 */
    @ToString.Exclude private final String secretKey;

    /** 存储键前缀，用于区分本应用的数据与其他使用方。 */
    private final String keyPrefix;

    /** 单个对象内容允许的字节数上限。 */
    private final Long maxObjectBytes;

    /**
     * 创建产物配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param enabled 是否启用产物对应的功能。
     * @param endpoint 已解析的服务接口地址，供实际网络请求使用。
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param accessKey 对象存储访问标识，与 secretKey 配合完成服务端认证。
     * @param secretKey 对象存储访问密钥，不属于客户端展示数据。
     * @param keyPrefix 存储键前缀，用于区分本应用的数据与其他使用方。
     * @param maxObjectBytes 单个对象内容允许的字节数上限。
     */
    public ArtifactProperties(
            boolean enabled,
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            String keyPrefix,
            Long maxObjectBytes) {
        this.enabled = enabled;
        this.endpoint = text(endpoint);
        this.bucket = text(bucket);
        this.accessKey = text(accessKey);
        this.secretKey = text(secretKey);
        this.keyPrefix = text(keyPrefix);
        this.maxObjectBytes = maxObjectBytes == null ? 100L * 1024 * 1024 : maxObjectBytes;
        if (enabled) {
            require(this.endpoint, "endpoint");
            require(this.bucket, "bucket");
            require(this.accessKey, "access-key");
            require(this.secretKey, "secret-key");
            require(this.keyPrefix, "key-prefix");
        }
    }

    /**
     * 转换为配置。
     *
     * @return 本次操作返回的BOS产物正文存储配置结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public BosArtifactContentStoreConfig toConfig() {
        if (!enabled) {
            throw new IllegalStateException("BOS Artifact store is disabled");
        }
        return new BosArtifactContentStoreConfig(
                endpoint, bucket, accessKey, secretKey, keyPrefix, maxObjectBytes);
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

    /**
     * 取得并校验产物配置。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前产物配置使用的字段，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void require(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Enabled BOS Artifact store requires " + field);
        }
    }
}
