package dev.horizen.agent.storage.bos;

import lombok.Getter;

/** BOS 内容存储配置；凭证由宿主从安全配置注入。 */
@Getter
public final class BosArtifactContentStoreConfig {
    /** 已解析的服务接口地址，供实际网络请求使用。 */
    private final String endpoint;

    /** 对象存储桶名称，限定内容对象的存储位置。 */
    private final String bucket;

    /** 对象存储访问标识，与 secretKey 配合完成服务端认证。 */
    private final String accessKey;

    /** 对象存储访问密钥，不属于客户端展示数据。 */
    private final String secretKey;

    /** 存储键前缀，用于区分本应用的数据与其他使用方。 */
    private final String keyPrefix;

    /** 单个对象内容允许的字节数上限。 */
    private final long maxObjectBytes;

    /**
     * 创建BOS产物正文存储配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param endpoint 已解析的服务接口地址，供实际网络请求使用。
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param accessKey 对象存储访问标识，与 secretKey 配合完成服务端认证。
     * @param secretKey 对象存储访问密钥，不属于客户端展示数据。
     * @param keyPrefix 存储键前缀，用于区分本应用的数据与其他使用方。
     * @param maxObjectBytes 单个对象内容允许的字节数上限。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public BosArtifactContentStoreConfig(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            String keyPrefix,
            long maxObjectBytes) {
        this.endpoint = text(endpoint, "endpoint");
        this.bucket = text(bucket, "bucket");
        this.accessKey = text(accessKey, "accessKey");
        this.secretKey = text(secretKey, "secretKey");
        this.keyPrefix = normalizePrefix(keyPrefix);
        if (maxObjectBytes <= 0) {
            throw new IllegalArgumentException("maxObjectBytes must be positive");
        }
        this.maxObjectBytes = maxObjectBytes;
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前BOS产物正文存储配置使用的字段，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /**
     * 规范化前缀。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String normalizePrefix(String value) {
        String prefix = text(value, "keyPrefix").replaceAll("^/+|/+$", "");
        if (prefix.isBlank() || prefix.contains("..")) {
            throw new IllegalArgumentException("keyPrefix must be a safe object prefix");
        }
        return prefix;
    }
}
