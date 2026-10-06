package dev.horizen.agent.evaluation;

import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 宿主桥接复用生产环境的会话和交互应用服务。
 */
public interface EvaluationHost {
    /**
     * 启动评测宿主。
     *
     * @param identity  可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param requestId 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     * @param step      当前评测宿主持有的步骤对象，供相应处理步骤使用。
     * @param timeout   本次等待允许持续的最长时间。
     * @param events    当前执行或历史事件集合，供持久化、回放与观测使用。
     */
    void start(
            ExecutionIdentity identity,
            String sessionId,
            String requestId,
            EvaluationProtocol.Step step,
            Duration timeout,
            Consumer<AgentRuntimeEvent> events);

    /**
     * 恢复评测宿主。
     *
     * @param identity    可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId   会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param pending     尚未完成处理的工作或计数，供刷新、关闭与容量控制使用。
     * @param interaction 当前评测宿主持有的交互对象，供相应处理步骤使用。
     * @param timeout     本次等待允许持续的最长时间。
     * @param events      当前执行或历史事件集合，供持久化、回放与观测使用。
     */
    void resume(
            ExecutionIdentity identity,
            String sessionId,
            AgentRuntimeEvent pending,
            EvaluationProtocol.Interaction interaction,
            Duration timeout,
            Consumer<AgentRuntimeEvent> events);

    /**
     * 取消评测宿主。
     *
     * @param identity  可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    void cancel(ExecutionIdentity identity, String sessionId);
}
