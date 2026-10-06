package dev.horizen.agent.application.interaction;

import dev.horizen.agent.interaction.approval.ApprovalRequest;

import lombok.Getter;

import java.util.Objects;

/**
 * 审批提交的应用层结果，区分是否已经提交与是否可以恢复执行。
 */
@Getter
public final class ApprovalResolution {
    /**
     * 本组件使用的 {@code ApprovalRequest} 状态或依赖，用于 request 的处理。
     */
    private final ApprovalRequest request;

    /**
     * 本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
     */
    private final boolean approved;

    /**
     * 创建审批Resolution，初始化该组件所需的状态、配置或依赖。
     *
     * @param request  当前操作的请求参数。
     * @param approved 本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
     */
    public ApprovalResolution(ApprovalRequest request, boolean approved) {
        this.request = Objects.requireNonNull(request, "request");
        this.approved = approved;
    }
}
