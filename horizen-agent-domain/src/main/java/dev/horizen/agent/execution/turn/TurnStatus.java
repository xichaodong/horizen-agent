package dev.horizen.agent.execution.turn;

/** 一轮 Agent 执行相对于所属 Session 的状态。 */
public enum TurnStatus {
    /** 执行仍在运行，模型或工具循环尚未进入终态。 */
    RUNNING,
    /** 执行等待原工具调用的审批决定，保留恢复上下文。 */
    WAITING_APPROVAL,
    /** 执行等待用户回答澄清问题，保留原执行定位。 */
    WAITING_ASK_USER,
    /** 取消请求已进入收敛流程，但尚未确认执行已经停止。 */
    CANCELLING,
    /** 执行已成功完成并进入终态。 */
    COMPLETED,
    /** 当前操作或执行失败，具体原因由对应的失败信息说明。 */
    FAILED,
    /** 执行或交互已取消，不再继续原处理。 */
    CANCELLED,
    /** 执行超过时限并进入超时终态。 */
    TIMED_OUT;

    /**
     * 判断终态。
     *
     * @return 是否属于完成、失败、取消或超时这四种结束状态。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    /** 判断当前状态是否允许转换到目标状态。 */
    public boolean canTransitionTo(TurnStatus target) {
        if (target == null || target == this || isTerminal()) {
            return false;
        }
        return switch (this) {
            case RUNNING ->
                    target == WAITING_APPROVAL
                            || target == WAITING_ASK_USER
                            || target == CANCELLING
                            || target == COMPLETED
                            || target == FAILED
                            || target == TIMED_OUT;
            case WAITING_APPROVAL ->
                    target == RUNNING
                            || target == CANCELLING
                            || target == FAILED
                            || target == TIMED_OUT;
            case WAITING_ASK_USER ->
                    target == RUNNING
                            || target == CANCELLING
                            || target == FAILED
                            || target == TIMED_OUT;
            case CANCELLING -> target == CANCELLED || target == FAILED || target == TIMED_OUT;
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT -> false;
        };
    }
}
