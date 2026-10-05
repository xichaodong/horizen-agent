package dev.horizen.agent.execution.session;

/** Session 自身的生命周期；执行状态属于 Turn。 */
public enum SessionStatus {
    /** 会话处于可继续使用的状态。 */
    ACTIVE,
    /** 会话已归档，不作为新的活跃执行入口。 */
    ARCHIVED
}
