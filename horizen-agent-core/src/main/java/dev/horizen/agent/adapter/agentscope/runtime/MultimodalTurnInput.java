package dev.horizen.agent.adapter.agentscope.runtime;

import dev.horizen.agent.runtime.api.AgentInputAttachment;

import lombok.Getter;

import java.util.List;

/** 仅供 RuntimeContext 使用的图像输入，不属于持久化 AgentState。 */
@Getter
public final class MultimodalTurnInput {
    /** 本次消息附带的输入资源，内容解析由运行时适配器完成。 */
    private final List<AgentInputAttachment> attachments;

    /**
     * 创建多模态执行输入，初始化该组件所需的状态、配置或依赖。
     *
     * @param attachments 本次消息附带的输入资源，内容解析由运行时适配器完成。
     */
    public MultimodalTurnInput(List<AgentInputAttachment> attachments) {
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
