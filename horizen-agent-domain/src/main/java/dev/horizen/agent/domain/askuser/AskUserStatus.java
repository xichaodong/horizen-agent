package dev.horizen.agent.domain.askuser;

/** 澄清请求的生命周期状态，用于回答、过期与恢复检查。 */
public enum AskUserStatus {
    /** 记录尚待处理或等待用户提交决定。 */
    PENDING,
    /** 澄清问题已提交有效回答，可继续原恢复流程。 */
    ANSWERED,
    /** 用户选择跳过本次澄清。 */
    SKIPPED,
    /** 记录已超过有效期，不再接受原决定或访问。 */
    EXPIRED,
    /** 执行或交互已取消，不再继续原处理。 */
    CANCELLED
}
