package dev.horizen.agent.domain.workspace.document;

import lombok.Data;

import java.time.Instant;
import java.util.Objects;

/** 支持版本管理的内联工作区文档；大型任务文件应存入内容存储。 */
@Data
public final class WorkspaceDocument {
    /** 组合资源归属与作用域的定位键，供仓储查询和更新使用。 */
    private final WorkspaceDocumentKey key;

    /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
    private final String content;

    /** 内容大小，单位为字节。 */
    private final long sizeBytes;

    /** 记录版本，用于乐观并发控制或区分协议版本。 */
    private final long version;

    /** 当前记录的创建时间。 */
    private final Instant createdAt;

    /** 当前记录最近一次更新的时间。 */
    private final Instant updatedAt;

    /**
     * 创建工作区文档，初始化该组件所需的状态、配置或依赖。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param sizeBytes 内容大小，单位为字节。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public WorkspaceDocument(
            WorkspaceDocumentKey key,
            String content,
            long sizeBytes,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.key = Objects.requireNonNull(key, "key");
        this.content = Objects.requireNonNull(content, "content");
        if (sizeBytes < 0 || version < 0) {
            throw new IllegalArgumentException("sizeBytes and version must not be negative");
        }
        this.sizeBytes = sizeBytes;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
