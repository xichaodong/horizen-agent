package dev.horizen.agent.domain.workspace.document;

import java.util.Map;

/** 工作区审计事实中的最小连续文本变更。 */
public final class WorkspaceTextDiff {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private WorkspaceTextDiff() {}

    /**
     * 计算或取得本方法声明的结果，供当前WorkspaceTextDiff处理步骤使用。
     *
     * @param old 当前工作区文本Diff使用的old，供其处理与状态记录使用。
     * @param next 当前工作区文本Diff使用的下一个，供其处理与状态记录使用。
     * @return 按返回类型约定组织的结果映射。
     */
    public static Map<String, String> difference(String old, String next) {
        int start = 0, end = 0;
        while (start < old.length()
                && start < next.length()
                && old.charAt(start) == next.charAt(start)) start++;
        while (end < old.length() - start
                && end < next.length() - start
                && old.charAt(old.length() - 1 - end) == next.charAt(next.length() - 1 - end))
            end++;
        return Map.of(
                "removed",
                old.substring(start, old.length() - end),
                "added",
                next.substring(start, next.length() - end));
    }
}
