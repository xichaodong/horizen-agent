package dev.horizen.agent.adapter.agentscope.runtime;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;

import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * AgentScope 同步调用会转发中间步骤，但最终结果只发送到子调用的输出端。
 */
public final class SubagentResultForwardingMiddleware implements MiddlewareBase {
    /**
     * 响应Agent。
     *
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input   本次处理的输入。
     * @param next    将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                subscriber -> {
                    var forwarding = AgentEventEmitter.fromForwardingContext(subscriber);
                    if (forwarding.isEmpty()) return next.apply(input);
                    return next.apply(input)
                            .doOnNext(
                                    event -> {
                                        if (event instanceof AgentResultEvent result
                                                && result.getResult() != null
                                                && (event.getSource() == null
                                                || event.getSource().isBlank())) {
                                            forwarding
                                                    .get()
                                                    .emit(new AgentResultEvent(result.getResult()));
                                        }
                                    });
                });
    }
}
