package dev.horizen.agent.domain.workspace.document;

import lombok.Data;

import java.util.List;
import java.util.Objects;

/** 云端工作区文档原子提交的结果。 */
@Data
public final class WorkspaceDocumentCommitResult {
    /** 已应用的状态标记，用于选择当前组件的处理路径。 */
    private final boolean applied;

    /** 按归属和作用域访问工作区文档的仓储。 */
    private final List<WorkspaceDocument> documents;

    /**
     * 创建工作区文档提交结果，初始化该组件所需的状态、配置或依赖。
     *
     * @param applied 已应用的状态标记，用于选择当前组件的处理路径。
     * @param documents 文档集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    public WorkspaceDocumentCommitResult(boolean applied, List<WorkspaceDocument> documents) {
        this.applied = applied;
        this.documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    }
}
