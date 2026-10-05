package dev.horizen.agent.runtime.api;

/** 同一个 owner/session 已有未结束 Turn 时拒绝新请求。 */
public final class SessionTurnBusyException extends IllegalStateException {
    /** 创建会话执行忙碌异常，初始化该组件所需的状态、配置或依赖。 */
    public SessionTurnBusyException() {
        super("session already has a running turn");
    }
}
