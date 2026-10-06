package dev.horizen.agent.storage.bos;

import com.baidubce.auth.DefaultBceCredentials;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.BosClientConfiguration;
import com.baidubce.services.bos.model.ObjectMetadata;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.util.Map;

/**
 * BCE Java SDK 的 BOS 调用实现。
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class BceBosObjectClient implements BosObjectClient {
    /**
     * 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    private final BosClient client;

    /**
     * 创建BCEBOS对象客户端。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的BCEBOS对象客户端结果。
     */
    static BceBosObjectClient create(BosArtifactContentStoreConfig properties) {
        BosClientConfiguration configuration = new BosClientConfiguration();
        configuration.setEndpoint(properties.getEndpoint());
        configuration.setCredentials(
                new DefaultBceCredentials(properties.getAccessKey(), properties.getSecretKey()));
        return new BceBosObjectClient(new BosClient(configuration));
    }

    /**
     * 写入BCEBOS对象客户端。
     *
     * @param bucket         对象存储桶名称，限定内容对象的存储位置。
     * @param key            当前对象的查找或写入键。
     * @param content        当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param mediaType      当前BCEBOS对象客户端使用的媒体类型，供其处理与状态记录使用。
     * @param checksumSha256 内容的 SHA-256 校验值，用于完整性校验。
     */
    @Override
    public void put(
            String bucket, String key, byte[] content, String mediaType, String checksumSha256) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType(mediaType);
        metadata.setUserMetadata(Map.of("horizen-sha256", checksumSha256));
        client.putObject(bucket, key, content, metadata);
    }

    /**
     * 读取BCEBOS对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key    当前对象的查找或写入键。
     * @return 本次处理取得或生成的内容字节。
     */
    @Override
    public byte[] get(String bucket, String key) {
        return client.getObjectContent(bucket, key);
    }

    /**
     * 创建下载URL。
     *
     * @param bucket           对象存储桶名称，限定内容对象的存储位置。
     * @param key              当前对象的查找或写入键。
     * @param expiresInSeconds 资源访问的有效时长，单位为秒。
     * @return 本次操作返回的URI结果。
     */
    @Override
    public URI createDownloadUrl(String bucket, String key, int expiresInSeconds) {
        return URI.create(client.generatePresignedUrl(bucket, key, expiresInSeconds).toString());
    }

    /**
     * 删除BCEBOS对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key    当前对象的查找或写入键。
     */
    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(bucket, key);
    }
}
