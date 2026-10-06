package dev.horizen.agent.skill;

import lombok.Data;

/**
 * 标识不可变 Skill 发布目录，不暴露底层存储协议。
 */
@Data
public final class SkillCatalogKey {
    /**
     * 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     */
    private final String agentKey;

    /**
     * 工作区或发布所属 Project 的标识，参与资源归属校验。
     */
    private final long projectId;

    /**
     * 创建Skill目录键，初始化该组件所需的状态、配置或依赖。
     *
     * @param agentKey  宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SkillCatalogKey(String agentKey, long projectId) {
        if (agentKey == null || !agentKey.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid agentKey");
        }
        if (projectId <= 0) {
            throw new IllegalArgumentException("projectId must be positive");
        }
        this.agentKey = agentKey;
        this.projectId = projectId;
    }
}
