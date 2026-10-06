package dev.horizen.agent.storage.bos;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

/**
 * 容量受限的归档存储；PUT 成功后替换对应槽位，被拒绝或失败时保留原内容。
 */
public final class BosWorkspaceSnapshotRepository implements WorkspaceSnapshotRepository {
    /**
     * 当前组件的诊断日志器。
     */
    private static final Logger log =
            Logger.getLogger(BosWorkspaceSnapshotRepository.class.getName());

    /**
     * 当前组件的配置与策略参数。
     */
    private final BosArtifactContentStoreConfig config;

    /**
     * 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    private final BosSnapshotObjectClient client;

    /**
     * transfers的并发准入许可，限制同时进行的处理数量。
     */
    private final Semaphore transfers;

    /**
     * 创建BOS工作区快照仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param config         当前组件的配置与策略参数。
     * @param concurrency    当前BOS工作区快照仓储使用的并发，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     */
    public BosWorkspaceSnapshotRepository(
            BosArtifactContentStoreConfig config, int concurrency, int timeoutSeconds) {
        this(
                config,
                new BceBosSnapshotObjectClient(config, concurrency, timeoutSeconds),
                concurrency);
    }

    /**
     * 创建BOS工作区快照仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param config      当前组件的配置与策略参数。
     * @param client      当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @param concurrency 当前BOS工作区快照仓储使用的并发，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    BosWorkspaceSnapshotRepository(
            BosArtifactContentStoreConfig config, BosSnapshotObjectClient client, int concurrency) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        if (concurrency <= 0) throw new IllegalArgumentException("concurrency must be positive");
        transfers = new Semaphore(concurrency);
    }

    /**
     * 上传BOS工作区快照仓储。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @param archive    当前BOS工作区快照仓储持有的归档对象，供相应处理步骤使用。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void upload(String snapshotId, InputStream archive) throws Exception {
        String key = key(snapshotId);
        Objects.requireNonNull(archive, "archive");
        acquire();
        Path spool = null;
        long started = System.nanoTime();
        try {
            spool = Files.createTempFile("horizen-bos-snapshot-", ".tar");
            MessageDigest digest = DigestUtils.newSha256();
            long bytes = 0;
            try (OutputStream output = Files.newOutputStream(spool)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = archive.read(buffer)) != -1) {
                    bytes += length;
                    if (bytes > config.getMaxObjectBytes())
                        throw new IOException("snapshot exceeds maxObjectBytes");
                    output.write(buffer, 0, length);
                    digest.update(buffer, 0, length);
                }
            }
            client.put(config.getBucket(), key, spool, HexFormat.of().formatHex(digest.digest()));
            log.info(
                    "Workspace snapshot uploaded [bytes="
                            + bytes
                            + ", elapsedMs="
                            + (System.nanoTime() - started) / 1000000
                            + "]");
        } catch (Exception error) {
            log.warning(
                    "Workspace snapshot upload failed [cause="
                            + error.getClass().getSimpleName()
                            + "]");
            throw error;
        } finally {
            try {
                if (spool != null) Files.deleteIfExists(spool);
            } finally {
                transfers.release();
            }
        }
    }

    /**
     * 下载BOS工作区快照仓储。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @return 本次操作返回的输入事件流结果。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public InputStream download(String snapshotId) throws Exception {
        String key = key(snapshotId);
        acquire();
        try {
            return new FilterInputStream(client.get(config.getBucket(), key)) {
                /** 当前内容字节或字节计数，用于传输、校验与容量控制。 */
                private long bytes;

                /** 组件是否已关闭，用于避免重复释放或继续接收新工作。 */
                private boolean closed;

                /**
                 * 读取匿名实现。
                 *
                 * @return 本次操作返回的整数结果。
                 */
                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0) count(1);
                    return value;
                }

                /**
                 * 读取匿名实现。
                 *
                 * @param buffer 当前匿名实现持有的缓冲对象，供相应处理步骤使用。
                 * @param offset 本次读取的起始偏移。
                 * @param length 当前匿名实现使用的长度，供其处理与状态记录使用。
                 * @return 本次操作返回的整数结果。
                 */
                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = in.read(buffer, offset, length);
                    if (count > 0) count(count);
                    return count;
                }

                /**
                 * 计算或取得本方法声明的结果，供当前anonymous处理步骤使用。
                 *
                 * @param length 当前匿名实现使用的长度，供其处理与状态记录使用。
                 * @return 本次操作返回的长整型结果。
                 */
                @Override
                public long skip(long length) throws IOException {
                    long remaining = length;
                    byte[] buffer = new byte[65536];
                    while (remaining > 0) {
                        int count = read(buffer, 0, (int) Math.min(remaining, buffer.length));
                        if (count < 0) break;
                        remaining -= count;
                    }
                    return length - remaining;
                }

                /**
                 * 完成当前操作的count步骤，按实现更新相应状态或依赖。
                 *
                 * @param length 当前匿名实现使用的长度，供其处理与状态记录使用。
                 * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
                 */
                private void count(long length) throws IOException {
                    bytes += length;
                    if (bytes > config.getMaxObjectBytes()) {
                        close();
                        throw new IOException("snapshot exceeds maxObjectBytes");
                    }
                }

                /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
                @Override
                public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try {
                        super.close();
                    } finally {
                        transfers.release();
                    }
                }
            };
        } catch (Exception error) {
            transfers.release();
            throw error;
        }
    }

    /**
     * 检查是否存在BOS工作区快照仓储。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(String snapshotId) throws Exception {
        String key = key(snapshotId);
        acquire();
        try {
            return client.exists(config.getBucket(), key);
        } finally {
            transfers.release();
        }
    }

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param id 目标对象的标识。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private String key(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}")) {
            throw new IllegalArgumentException("Invalid snapshotId");
        }
        return config.getKeyPrefix() + "/" + id + ".tar";
    }

    /**
     * 取得BOS工作区快照仓储。
     *
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void acquire() throws IOException {
        if (!transfers.tryAcquire()) throw new IOException("snapshot transfer capacity exhausted");
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     */
    @Override
    public void close() {
        client.close();
    }
}
