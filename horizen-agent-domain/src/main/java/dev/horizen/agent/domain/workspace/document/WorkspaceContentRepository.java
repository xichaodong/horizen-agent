package dev.horizen.agent.domain.workspace.document;

import java.net.URI;
import java.util.Optional;

/** 不可变工作区文件内容；引用不透明且不包含访问凭据。 */
public interface WorkspaceContentRepository extends AutoCloseable {
    /**
     * 上传工作区正文仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次处理生成或读取的文本。
     */
    String upload(WorkspaceDocumentKey key, byte[] content);

    /**
     * 下载工作区正文仓储。
     *
     * @param reference 当前工作区正文仓储使用的引用，供其处理与状态记录使用。
     * @param maximumBytes 最大的字节数，用于容量或传输限制。
     * @return 本次处理取得或生成的内容字节。
     */
    byte[] download(String reference, long maximumBytes);

    /**
     * 删除工作区正文仓储。
     *
     * @param reference 当前工作区正文仓储使用的引用，供其处理与状态记录使用。
     */
    void delete(String reference);

    /**
     * 为旧调用方派生不可变目录归档，不形成另一套版本依据。
     */
    default String uploadDerived(WorkspaceDocumentKey key, byte[] content) {
        return upload(key, content);
    }

    /**
     * 下载URL。
     *
     * @param reference 当前工作区正文仓储使用的引用，供其处理与状态记录使用。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    default Optional<URI> downloadUrl(String reference) {
        return Optional.empty();
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    default void close() {}
}
