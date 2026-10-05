package dev.horizen.agent.domain.presentation;

import lombok.Getter;

/** 运行时事件负载，将声明式展示块关联到生成它的工具调用。 */
@Getter
public final class PresentationOutput {
    /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
    private final String toolCallId;

    /** 当前需要保存或展示的结构化呈现块。 */
    private final PresentationBlock block;

    /**
     * 创建呈现输出，初始化该组件所需的状态、配置或依赖。
     *
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param block 当前呈现输出持有的块对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public PresentationOutput(String toolCallId, PresentationBlock block) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId is required");
        }
        if (block == null) throw new IllegalArgumentException("block is required");
        this.toolCallId = toolCallId;
        this.block = block;
    }
}
