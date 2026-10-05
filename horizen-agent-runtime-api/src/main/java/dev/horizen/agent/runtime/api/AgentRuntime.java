package dev.horizen.agent.runtime.api;

import reactor.core.publisher.Flux;

import java.util.Optional;

/** 面向宿主的统一执行入口，一个长生命周期 Agent 实例可以服务多个隔离主体。 */
public interface AgentRuntime extends AutoCloseable {
    /**
     * 在宿主提供的归属与会话范围内开始或恢复一次执行，返回模型、工具和终态事件流。
     *
     * @param request 当前操作的请求参数。
     * @return 执行事件流；具体实现可以延迟到订阅时开始工作，调用方应处理终态与失败。
     */
    Flux<AgentRuntimeEvent> stream(AgentTurnRequest request);

    /** 查询某个 Session 最新一轮执行状态。 */
    default Optional<SessionExecutionState> sessionExecution(String ownerKey, String sessionId) {
        return Optional.empty();
    }

    /** 将当前 Turn 标记为宿主超时，并请求 AgentScope 中断该 Session。 */
    default boolean timeoutCurrentTurn(String ownerKey, String sessionId) {
        return false;
    }

    /** 校验 turnId 后将当前 Turn 标记为宿主超时。 */
    default boolean timeoutCurrentTurn(String ownerKey, String sessionId, String expectedTurnId) {
        return timeoutCurrentTurn(ownerKey, sessionId);
    }

    /** 校验 turnId 后，请求 AgentScope 中断当前 Turn。 */
    default boolean interruptCurrentTurn(String ownerKey, String sessionId, String expectedTurnId) {
        return false;
    }

    /** 执行实例失联后，把共享 AgentScope 状态结束为失败。 */
    default boolean failCurrentTurn(
            String ownerKey, String sessionId, String expectedTurnId, String failureCode) {
        return false;
    }

    /** 结束运行时使用并执行其关闭策略；默认实现不持有需要释放的资源。 */
    @Override
    default void close() {}
}
