package dev.horizen.agent.domain.workspace.release;

import lombok.Value;

/**
 * Project 与 Agent 组成的发布目录定位键，避免跨目录混用发布。
 */
@Value
public class AgentCatalogKey {
    /**
     * 工作区或发布所属 Project 的标识，参与资源归属校验。
     */
    long projectId;

    /**
     * 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     */
    String agentKey;

    /**
     * 创建Agent目录键，初始化该组件所需的状态、配置或依赖。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey  宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public AgentCatalogKey(long projectId, String agentKey) {
        if (projectId <= 0
                || agentKey == null
                || !agentKey.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid agent catalog");
        this.projectId = projectId;
        this.agentKey = agentKey;
    }
}
