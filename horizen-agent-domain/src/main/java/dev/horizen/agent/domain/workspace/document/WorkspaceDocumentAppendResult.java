package dev.horizen.agent.domain.workspace.document;

import lombok.Data;

import java.util.Objects;

/** 幂等追加的结果。 */
@Data
public final class WorkspaceDocumentAppendResult {
    /** 当前操作取得或提交的工作区文档。 */
    private final WorkspaceDocument document;

    /** 已应用的状态标记，用于选择当前组件的处理路径。 */
    private final boolean applied;

    /**
     * 创建工作区文档追加结果，初始化该组件所需的状态、配置或依赖。
     *
     * @param document 当前工作区文档追加结果持有的文档对象，供相应处理步骤使用。
     * @param applied 已应用的状态标记，用于选择当前组件的处理路径。
     */
    public WorkspaceDocumentAppendResult(WorkspaceDocument document, boolean applied) {
        this.document = Objects.requireNonNull(document, "document");
        this.applied = applied;
    }
}
