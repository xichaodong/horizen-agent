package dev.horizen.agent.storage.bos;

import com.baidubce.BceServiceException;
import com.baidubce.auth.DefaultBceCredentials;
import com.baidubce.http.DefaultRetryPolicy;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.BosClientConfiguration;
import com.baidubce.services.bos.model.ObjectMetadata;

import org.apache.http.ConnectionClosedException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/** 用 BCE BOS SDK 读写快照对象内容，保留快照存储端口的边界。 */
final class BceBosSnapshotObjectClient implements BosSnapshotObjectClient {
    /** 当前适配器使用的远端客户端，供实际网络或服务请求使用。 */
    private final BosClient client;

    /**
     * 创建BCEBOS快照对象客户端，初始化该组件所需的状态、配置或依赖。
     *
     * @param config 当前组件的配置与策略参数。
     * @param concurrency 当前BCEBOS快照对象客户端使用的并发，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     */
    BceBosSnapshotObjectClient(
            BosArtifactContentStoreConfig config, int concurrency, int timeoutSeconds) {
        BosClientConfiguration sdk = new BosClientConfiguration();
        sdk.setEndpoint(config.getEndpoint());
        sdk.setCredentials(new DefaultBceCredentials(config.getAccessKey(), config.getSecretKey()));
        sdk.setMaxConnections(concurrency);
        sdk.setConnectionTimeoutInMillis(Math.min(timeoutSeconds * 1000, 10000));
        sdk.setSocketTimeoutInMillis(timeoutSeconds * 1000);
        sdk.setRetryPolicy(new DefaultRetryPolicy(0, 0));
        client = new BosClient(sdk);
    }

    /**
     * 写入BCEBOS快照对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     * @param file 当前BCEBOS快照对象客户端持有的文件对象，供相应处理步骤使用。
     * @param sha256 内容的 SHA-256 摘要，参与制品完整性验证。
     */
    @Override
    public void put(String bucket, String key, Path file, String sha256) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("application/x-tar");
        metadata.setUserMetadata(Map.of("horizen-sha256", sha256));
        client.putObject(bucket, key, file.toFile(), metadata);
    }

    /**
     * 读取BCEBOS快照对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     * @return 本次操作返回的输入事件流结果。
     */
    @Override
    public InputStream get(String bucket, String key) {
        return new FilterInputStream(client.getObject(bucket, key).getObjectContent()) {
            /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } catch (ConnectionClosedException expectedOnEarlyClose) {
                    // BCE 0.10.423 会先关闭 HTTP 响应，再关闭或读完响应实体。
                    // 此处的关闭异常表示调用者主动停止读取；读取中的提前 EOF 仍需向上传播并拒绝恢复。
                }
            }
        };
    }

    /**
     * 检查是否存在BCEBOS快照对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(String bucket, String key) {
        try {
            client.getObjectMetadata(bucket, key);
            return true;
        } catch (BceServiceException error) {
            if (error.getStatusCode() == 404 && "NoSuchKey".equals(error.getErrorCode()))
                return false;
            // HEAD 响应通常没有响应体，SDK 无法解码 NoSuchKey。将其视为工作区对象缺失前，
            // 需要单独验证存储桶。
            if (error.getStatusCode() == 404
                    && error.getErrorCode() == null
                    && client.doesBucketExist(bucket)) return false;
            throw error; // 权限错误和服务中断不能被视为全新工作区。
        }
    }

    /**
     * 删除BCEBOS快照对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     */
    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(bucket, key);
    }

    /**
     * 下载URL。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     * @return 本次操作返回的URI结果。
     */
    @Override
    public URI downloadUrl(String bucket, String key) {
        return URI.create(client.generatePresignedUrl(bucket, key, -1).toString());
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public void close() {
        client.shutdown();
    }
}
