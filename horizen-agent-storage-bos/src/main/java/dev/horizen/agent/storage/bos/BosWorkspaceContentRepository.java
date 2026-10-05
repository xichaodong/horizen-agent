package dev.horizen.agent.storage.bos;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.common.io.BoundedStreams;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 每次尝试生成不可变对象，限制传输量并在流式传输中检查大小。 */
public final class BosWorkspaceContentRepository implements WorkspaceContentRepository {
    /** 当前组件的配置与策略参数。 */
    private final BosArtifactContentStoreConfig config;

    /** 当前适配器使用的远端客户端，供实际网络或服务请求使用。 */
    private final BosSnapshotObjectClient client;

    /** transfers的并发准入许可，限制同时进行的处理数量。 */
    private final Semaphore transfers = new Semaphore(2);

    /**
     * 创建BOS工作区正文仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param config 当前组件的配置与策略参数。
     */
    public BosWorkspaceContentRepository(BosArtifactContentStoreConfig config) {
        this.config = Objects.requireNonNull(config);
        client = new BceBosSnapshotObjectClient(config, 2, 120);
    }

    /**
     * 上传BOS工作区正文仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public String upload(WorkspaceDocumentKey key, byte[] content) {
        if (content.length > config.getMaxObjectBytes())
            throw new IllegalArgumentException("Workspace file exceeds upload limit");
        enter();
        Path spool = null;
        try {
            String reference = config.getKeyPrefix() + "/objects/" + UUID.randomUUID();
            spool = Files.createTempFile("horizen-workspace-body-", ".bin");
            Files.write(spool, content);
            String checksum = DigestUtils.sha256Hex(content);
            client.put(config.getBucket(), reference, spool, checksum);
            return reference;
        } catch (Exception error) {
            throw new IllegalStateException("Workspace upload failed", error);
        } finally {
            if (spool != null)
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException ignored) {
                }
            transfers.release();
        }
    }

    /**
     * 下载BOS工作区正文仓储。
     *
     * @param reference 当前BOS工作区正文仓储使用的引用，供其处理与状态记录使用。
     * @param maximumBytes 最大的字节数，用于容量或传输限制。
     * @return 本次处理取得或生成的内容字节。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws SecurityException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public byte[] download(String reference, long maximumBytes) {
        if (reference == null || !reference.startsWith(config.getKeyPrefix() + "/objects/"))
            throw new SecurityException("Workspace content reference is outside its provider");
        long limit = Math.min(maximumBytes, config.getMaxObjectBytes());
        enter();
        try (InputStream input = client.get(config.getBucket(), reference)) {
            return BoundedStreams.read(input, limit);
        } catch (Exception error) {
            throw new IllegalStateException("Workspace download failed", error);
        } finally {
            transfers.release();
        }
    }

    /**
     * 上传Derived。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public String uploadDerived(WorkspaceDocumentKey key, byte[] content) {
        if (content.length > 21L * 1024 * 1024)
            throw new IllegalArgumentException("Workspace archive exceeds limit");
        enter();
        Path spool = null;
        try {
            String checksum = DigestUtils.sha256Hex(content);
            String reference = config.getKeyPrefix() + "/objects/derived-" + checksum;
            if (!client.exists(config.getBucket(), reference)) {
                spool = Files.createTempFile("workspace-derived-", ".zip");
                Files.write(spool, content);
                client.put(config.getBucket(), reference, spool, checksum);
            }
            return reference;
        } catch (Exception e) {
            throw new IllegalStateException("Workspace archive upload failed", e);
        } finally {
            if (spool != null)
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException ignored) {
                }
            transfers.release();
        }
    }

    /**
     * 下载URL。
     *
     * @param reference 当前BOS工作区正文仓储使用的引用，供其处理与状态记录使用。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     * @throws SecurityException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Optional<URI> downloadUrl(String reference) {
        if (reference == null || !reference.startsWith(config.getKeyPrefix() + "/objects/"))
            throw new SecurityException("Invalid workspace archive reference");
        return Optional.of(client.downloadUrl(config.getBucket(), reference));
    }

    /**
     * 删除BOS工作区正文仓储。
     *
     * @param reference 当前BOS工作区正文仓储使用的引用，供其处理与状态记录使用。
     * @throws SecurityException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void delete(String reference) {
        if (reference == null || !reference.startsWith(config.getKeyPrefix() + "/objects/"))
            throw new SecurityException("Workspace content reference is outside its provider");
        client.delete(config.getBucket(), reference);
    }

    /**
     * 完成当前操作的enter步骤，按实现更新相应状态或依赖。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void enter() {
        if (!transfers.tryAcquire())
            throw new IllegalStateException("Workspace transfer capacity exhausted");
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public void close() {
        client.close();
    }
}
