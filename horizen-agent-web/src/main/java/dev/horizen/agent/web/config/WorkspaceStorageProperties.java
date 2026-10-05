package dev.horizen.agent.web.config;

import dev.horizen.agent.storage.bos.BosArtifactContentStoreConfig;

import lombok.Data;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 所有可变工作区文件均通过此云端内容提供器访问。 */
@ConfigurationProperties("horizen.agent.workspace-storage")
@Data
public class WorkspaceStorageProperties {
    /** 对象存储访问标识，与 secretKey 配合完成服务端认证。 */
    /** 对象存储桶名称，限定内容对象的存储位置。 */
    /** 已解析的服务接口地址，供实际网络请求使用。 */
    private String endpoint = "", bucket = "", accessKey = "";

    /** 对象存储访问密钥，不属于客户端展示数据。 */
    @ToString.Exclude private String secretKey = "";

    /** 存储键前缀，用于区分本应用的数据与其他使用方。 */
    private String keyPrefix = "agentFiles/horizen-workspace-files";

    /** 单个文件内容允许的字节数上限。 */
    private long maxFileBytes = 10L * 1024 * 1024;

    /**
     * 脱敏工作区存储配置。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    public String redact(String value) {
        return value == null || secretKey.isBlank() ? value : value.replace(secretKey, "***");
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param bytes 当前操作处理的内容字节。
     * @return 本次操作返回的BOS产物正文存储配置结果。
     */
    public BosArtifactContentStoreConfig directoryConfig(long bytes) {
        return new BosArtifactContentStoreConfig(
                endpoint, bucket, accessKey, secretKey, keyPrefix + "/sessions", bytes);
    }

    /**
     * 转换为配置。
     *
     * @return 本次操作返回的BOS产物正文存储配置结果。
     */
    public BosArtifactContentStoreConfig toConfig() {
        return new BosArtifactContentStoreConfig(
                endpoint, bucket, accessKey, secretKey, keyPrefix, maxFileBytes);
    }
}
