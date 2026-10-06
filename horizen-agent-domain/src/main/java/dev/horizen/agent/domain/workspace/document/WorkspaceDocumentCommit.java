package dev.horizen.agent.domain.workspace.document;

import lombok.Data;

import java.util.List;
import java.util.Objects;

/**
 * 同一所有者、Agent 和作用域内的小文档短事务，可按条件替换一个文档，并追加一条或多条幂等记录。
 */
@Data
public final class WorkspaceDocumentCommit {
    /**
     * 本次替换写入的目标正文与版本校验信息。
     */
    private final WorkspaceDocumentReplace replacement;

    /**
     * appends的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    private final List<WorkspaceDocumentAppend> appends;

    /**
     * 创建工作区文档提交，初始化该组件所需的状态、配置或依赖。
     *
     * @param replacement 当前工作区文档提交持有的替换对象，供相应处理步骤使用。
     * @param appends     appends的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public WorkspaceDocumentCommit(
            WorkspaceDocumentReplace replacement, List<WorkspaceDocumentAppend> appends) {
        this.replacement = replacement;
        this.appends = List.copyOf(Objects.requireNonNull(appends, "appends"));
        if (this.appends.isEmpty()) {
            throw new IllegalArgumentException("workspace commit must contain an append");
        }
        WorkspaceDocumentKey scope = this.appends.get(0).getKey();
        for (WorkspaceDocumentAppend append : this.appends) {
            requireSameScope(scope, append.getKey());
        }
        if (replacement != null) requireSameScope(scope, replacement.getKey());
    }

    /**
     * 取得并校验相同作用域。
     *
     * @param expected 当前工作区文档提交持有的预期对象，供相应处理步骤使用。
     * @param actual   当前工作区文档提交持有的实际对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requireSameScope(
            WorkspaceDocumentKey expected, WorkspaceDocumentKey actual) {
        if (!expected.getOwnerKey().equals(actual.getOwnerKey())
                || !expected.getAgentKey().equals(actual.getAgentKey())
                || !expected.getScopeKey().equals(actual.getScopeKey())) {
            throw new IllegalArgumentException("workspace commit must stay in one scope");
        }
    }
}
