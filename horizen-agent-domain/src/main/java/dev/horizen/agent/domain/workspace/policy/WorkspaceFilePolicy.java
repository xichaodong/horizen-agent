package dev.horizen.agent.domain.workspace.policy;

import lombok.Value;

import java.util.Set;

/**
 * 持久化层和运行时适配器共享的工作区分类及所有权规则。
 */
public final class WorkspaceFilePolicy {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private WorkspaceFilePolicy() {
    }

    /**
     * 生成当前操作所需的kind文本，供调用方继续处理。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次处理生成或读取的文本。
     */
    public static String kind(String path) {
        if (path.equals("AGENTS.md")) return "AGENTS";
        if (path.equals("MEMORY.md")) return "MEMORY";
        if (path.equals("PLAN.md") || path.startsWith("plans/")) return "PLAN";
        if (path.equals("tools.json")) return "TOOLS";
        if (path.startsWith("knowledge/")) return "KNOWLEDGE";
        if (path.startsWith("skills/")) return "SKILL";
        if (path.startsWith("subagents/")) return "SUBAGENT";
        if (path.startsWith(".skills-cache/")) return "SKILL_CACHE";
        if (path.startsWith("memory/")) return "MEMORY_LOG";
        return "DOCUMENT";
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的分类结果。
     */
    public static Classification classify(String path) {
        String area =
                path.startsWith("sessions/")
                        ? "SESSION"
                        : path.startsWith("tasks/") ? "TASK" : "USER";
        String kind = kind(path);
        String policy =
                switch (kind) {
                    case "AGENTS", "TOOLS", "KNOWLEDGE", "SKILL", "SUBAGENT", "SKILL_CACHE" -> "SERVER";
                    default -> "AGENT";
                };
        return new Classification(area, kind, policy);
    }

    /**
     * 检查serverOwned对应的条件，供调用方选择后续处理分支。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public static boolean serverOwned(String path) {
        return "SERVER".equals(classify(path).getPolicy())
                || Set.of("knowledge", "skills", "subagents", ".skills-cache").contains(path);
    }

    /**
     * 工作区文件类型与允许写入方式的策略分类。
     */
    @Value
    public static class Classification {
        /**
         * 文件所属工作区区域，用于区分共享文档、会话任务与发布内容。
         */
        String area;

        /**
         * 当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。
         */
        String kind;

        /**
         * 当前对象使用的处理策略，决定校验、权限或执行边界。
         */
        String policy;
    }
}
