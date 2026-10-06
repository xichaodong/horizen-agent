package dev.horizen.agent.storage.jdbc.repository.workspace;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 对象存储 I/O 和完整性检查；SQL 仓储在事务外调用。
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class WorkspaceObjectContent {
    /**
     * 当前组件的诊断日志器。
     */
    private static final System.Logger log =
            System.getLogger(WorkspaceObjectContent.class.getName());

    /**
     * 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    private final WorkspaceContentRepository contents;

    /**
     * 最大的字节数，用于容量或传输限制。
     */
    private final long maximumBytes;

    /**
     * 读取工作区对象正文。
     *
     * @param reference 当前工作区对象正文使用的引用，供其处理与状态记录使用。
     * @param checksum  内容校验值，用于确认传输或存储后的内容一致。
     * @param size      当前内容或集合的大小，计量方式由所属资源协议定义。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    String read(String reference, String checksum, long size) {
        byte[] bytes = contents.download(reference, maximumBytes);
        if (bytes.length != size || !DigestUtils.sha256Hex(bytes).equals(checksum))
            throw new IllegalStateException("Workspace object integrity mismatch");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 上传工作区对象正文。
     *
     * @param key     当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次操作返回的上报成功数结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    Uploaded upload(WorkspaceDocumentKey key, String content) {
        byte[] bytes = Objects.requireNonNull(content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes)
            throw new IllegalArgumentException("Workspace file exceeds " + maximumBytes + " bytes");
        return new Uploaded(
                contents.upload(key, bytes), DigestUtils.sha256Hex(bytes), bytes.length);
    }

    /**
     * 完成当前操作的discard步骤，按实现更新相应状态或依赖。
     *
     * @param reference 当前工作区对象正文使用的引用，供其处理与状态记录使用。
     */
    void discard(String reference) {
        if (reference == null) return;
        try {
            contents.delete(reference);
        } catch (RuntimeException error) {
            log.log(
                    System.Logger.Level.WARNING,
                    "Workspace object cleanup failed ({0})",
                    error.getClass().getSimpleName());
        }
    }

    /**
     * 工作区对象正文内部的上报成功数，封装该步骤需要的状态或输入输出。
     */
    @Value
    static class Uploaded {
        /**
         * 内容对象的持久引用，供后续读取实际字节。
         */
        String reference;

        /**
         * 内容校验值，用于确认传输或存储后的内容一致。
         */
        String checksum;

        /**
         * 当前内容字节或字节计数，用于传输、校验与容量控制。
         */
        long bytes;
    }
}
