package dev.horizen.agent.interaction.approval;

/**
 * 一次工具审批的处理状态。
 */
public enum ApprovalStatus {
    /**
     * 记录尚待处理或等待用户提交决定。
     */
    PENDING,
    /**
     * 用户已允许执行原工具调用。
     */
    APPROVED,
    /**
     * 用户已拒绝执行原工具调用。
     */
    DENIED,
    /**
     * 记录已超过有效期，不再接受原决定或访问。
     */
    EXPIRED,
    /**
     * 执行或交互已取消，不再继续原处理。
     */
    CANCELLED
}
