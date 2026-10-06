package dev.horizen.agent.domain.workspace.document;

import lombok.Data;

import java.util.Objects;

/**
 * 云端工作区提交中的比较并交换替换操作。
 */
@Data
public final class WorkspaceDocumentReplace {
    /**
     * 组合资源归属与作用域的定位键，供仓储查询和更新使用。
     */
    private final WorkspaceDocumentKey key;

    /**
     * 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     */
    private final String content;

    /**
     * 调用方观察到的版本，更新时用于识别并发修改。
     */
    private final long expectedVersion;

    /**
     * 创建工作区文档替换，初始化该组件所需的状态、配置或依赖。
     *
     * @param key             当前对象的查找或写入键。
     * @param content         当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public WorkspaceDocumentReplace(
            WorkspaceDocumentKey key, String content, long expectedVersion) {
        this.key = Objects.requireNonNull(key, "key");
        this.content = Objects.requireNonNull(content, "content");
        if (expectedVersion <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        this.expectedVersion = expectedVersion;
    }
}
