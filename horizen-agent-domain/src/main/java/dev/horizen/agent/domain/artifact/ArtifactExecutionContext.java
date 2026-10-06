package dev.horizen.agent.domain.artifact;

import lombok.Getter;

/**
 * 当前 Turn 的 Artifact 发布上下文，只由 Runtime 注入。
 */
@Getter
public final class ArtifactExecutionContext {
    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private final String turnId;

    /**
     * 创建产物执行上下文，初始化该组件所需的状态、配置或依赖。
     *
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ArtifactExecutionContext(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        this.turnId = turnId;
    }
}
