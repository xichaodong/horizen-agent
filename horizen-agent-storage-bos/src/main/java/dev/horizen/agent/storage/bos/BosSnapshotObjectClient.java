package dev.horizen.agent.storage.bos;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;

/**
 * 流式 BOS 操作，与基于字节数组的 Artifact 适配器分离。
 */
interface BosSnapshotObjectClient extends AutoCloseable {
    /**
     * 写入BOS快照对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key    当前对象的查找或写入键。
     * @param file   当前BOS快照对象客户端持有的文件对象，供相应处理步骤使用。
     * @param sha256 内容的 SHA-256 摘要，参与制品完整性验证。
     */
    void put(String bucket, String key, Path file, String sha256);

    /**
     * 读取BOS快照对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key    当前对象的查找或写入键。
     * @return 本次操作返回的输入事件流结果。
     */
    InputStream get(String bucket, String key);

    /**
     * 检查是否存在BOS快照对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key    当前对象的查找或写入键。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean exists(String bucket, String key);

    /**
     * 删除BOS快照对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key    当前对象的查找或写入键。
     */
    default void delete(String bucket, String key) {
    }

    /**
     * 下载URL。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key    当前对象的查找或写入键。
     * @return 本次操作返回的URI结果。
     * @throws UnsupportedOperationException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    default URI downloadUrl(String bucket, String key) {
        throw new UnsupportedOperationException("Download links unavailable");
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     */
    @Override
    default void close() {
    }
}
