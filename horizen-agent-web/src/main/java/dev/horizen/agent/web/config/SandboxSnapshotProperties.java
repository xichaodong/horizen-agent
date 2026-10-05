package dev.horizen.agent.web.config;

import dev.horizen.agent.storage.bos.BosArtifactContentStoreConfig;

import lombok.Data;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立的快照前缀和限制；凭据留在 Java 宿主中。 */
@ConfigurationProperties(prefix = "horizen.agent.sandbox.snapshot.bos")
@Data
public class SandboxSnapshotProperties {
    /** 是否启用沙箱快照对应的功能。 */
    private boolean enabled;

    /** 已解析的服务接口地址，供实际网络请求使用。 */
    private String endpoint = "";

    /** 对象存储桶名称，限定内容对象的存储位置。 */
    private String bucket = "";

    /** 对象存储访问标识，与 secretKey 配合完成服务端认证。 */
    @ToString.Exclude private String accessKey = "";

    /** 对象存储访问密钥，不属于客户端展示数据。 */
    @ToString.Exclude private String secretKey = "";

    /** 存储键前缀，用于区分本应用的数据与其他使用方。 */
    private String keyPrefix = "agentFiles/horizen-sandbox-snapshots";

    /** 单个工作区归档允许的字节数上限。 */
    private long maxArchiveBytes = 64L * 1024 * 1024;

    /** 归档或目录中允许处理的条目数量上限。 */
    private int maxEntries = 10000;

    /** 同时执行当前处理步骤的并发数量上限。 */
    private int concurrency = 2;

    /** 超时，单位为秒。 */
    private int timeoutSeconds = 120;

    /**
     * 转换为配置。
     *
     * @return 本次操作返回的BOS产物正文存储配置结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public BosArtifactContentStoreConfig toConfig() {
        if (!enabled) throw new IllegalStateException("BOS snapshots disabled");
        if (maxArchiveBytes <= 0
                || maxEntries <= 0
                || concurrency < 1
                || concurrency > 2
                || timeoutSeconds <= 0
                || timeoutSeconds > 300) {
            throw new IllegalArgumentException("Invalid BOS snapshot limits");
        }
        return new BosArtifactContentStoreConfig(
                endpoint, bucket, accessKey, secretKey, keyPrefix, maxArchiveBytes);
    }
}
