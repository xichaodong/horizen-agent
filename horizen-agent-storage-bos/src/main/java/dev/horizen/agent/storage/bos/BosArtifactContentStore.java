package dev.horizen.agent.storage.bos;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.artifact.ArtifactContent;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactContentWrite;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 使用 BOS 保存 Artifact 内容，contentRef 始终是受限的 BOS object key。
 */
public final class BosArtifactContentStore implements ArtifactContentStore {
    /**
     * 当前组件的配置与策略参数。
     */
    private final BosArtifactContentStoreConfig config;

    /**
     * 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    private final BosObjectClient client;

    /**
     * 创建BOS产物正文存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param config 当前组件的配置与策略参数。
     */
    public BosArtifactContentStore(BosArtifactContentStoreConfig config) {
        this(config, BceBosObjectClient.create(config));
    }

    /**
     * 创建BOS产物正文存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param config 当前组件的配置与策略参数。
     * @param client 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    BosArtifactContentStore(BosArtifactContentStoreConfig config, BosObjectClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 写入BOS产物正文存储。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的产物正文结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public ArtifactContent put(ArtifactContentWrite request) {
        Objects.requireNonNull(request, "request");
        byte[] content = request.content();
        if (content.length > config.getMaxObjectBytes()) {
            throw new IllegalArgumentException("artifact content exceeds maxObjectBytes");
        }
        String checksum = sha256(content);
        String key = keyFor(request.getOwnerKey(), request.getArtifactId());
        client.put(config.getBucket(), key, content, request.getMediaType(), checksum);
        return new ArtifactContent(key, content.length, checksum);
    }

    /**
     * 读取BOS产物正文存储。
     *
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @return 本次处理取得或生成的内容字节。
     */
    @Override
    public byte[] get(String contentRef) {
        return client.get(config.getBucket(), validateContentRef(contentRef));
    }

    /**
     * 创建下载URL。
     *
     * @param contentRef       内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @param expiresInSeconds 资源访问的有效时长，单位为秒。
     * @return 本次操作返回的URI结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public URI createDownloadUrl(String contentRef, int expiresInSeconds) {
        if (expiresInSeconds == 0 || expiresInSeconds < -1) {
            throw new IllegalArgumentException("expiresInSeconds must be positive or -1");
        }
        return client.createDownloadUrl(
                config.getBucket(), validateContentRef(contentRef), expiresInSeconds);
    }

    /**
     * 删除BOS产物正文存储。
     *
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     */
    @Override
    public void delete(String contentRef) {
        client.delete(config.getBucket(), validateContentRef(contentRef));
    }

    /**
     * 生成当前操作所需的keyFor文本，供调用方继续处理。
     *
     * @param ownerKey   宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @return 本次处理生成或读取的文本。
     */
    private String keyFor(String ownerKey, String artifactId) {
        return config.getKeyPrefix()
                + "/"
                + sha256(ownerKey.getBytes(StandardCharsets.UTF_8))
                + "/"
                + artifactId;
    }

    /**
     * 校验正文引用。
     *
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private String validateContentRef(String contentRef) {
        if (contentRef == null || !contentRef.startsWith(config.getKeyPrefix() + "/")) {
            throw new IllegalArgumentException("contentRef is outside configured BOS prefix");
        }
        return contentRef;
    }

    /**
     * 生成当前操作所需的sha256文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String sha256(byte[] value) {
        return DigestUtils.sha256Hex(value);
    }
}
