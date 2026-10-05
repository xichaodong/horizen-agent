package dev.horizen.agent.evaluation;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;

import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** 在中间件边界采集模型调用事实，包含继承上下文的子调用。 */
public final class EvaluationModelMiddleware implements MiddlewareBase {
    /** 作用域的固定取值，用于相应策略和边界判断。 */
    private static final String SCOPE = EvaluationModelMiddleware.class.getName();

    /**
     * 响应Agent。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input 本次处理的输入。
     * @param next 将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        EvaluationFixture fixture = context.get(EvaluationFixture.class);
        return fixture == null
                ? next.apply(input)
                : next.apply(input).contextWrite(view -> view.put(SCOPE, fixture));
    }

    /**
     * 响应模型调用。
     * 并发状态更新包含比较交换操作。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input 本次处理的输入。
     * @param next 将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                view -> {
                    EvaluationFixture fixture = context.get(EvaluationFixture.class);
                    if (fixture == null) fixture = view.getOrDefault(SCOPE, null);
                    if (fixture == null) return next.apply(input);
                    EvaluationFixture scope = fixture;
                    String id = UUID.randomUUID().toString();
                    long started = System.nanoTime();
                    StringBuilder response = new StringBuilder();
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("model", input.model().getModelName());
                    payload.put("agentName", agent.getName());
                    payload.put("modelCallId", id);
                    AtomicBoolean recorded = new AtomicBoolean();
                    Consumer<String> record =
                            signal -> {
                                if (!recorded.compareAndSet(false, true)) return;
                                payload.put(
                                        "durationMs", (System.nanoTime() - started) / 1_000_000);
                                payload.put("response", response.toString());
                                payload.put("status", signal);
                                scope.modelEvidence(payload);
                            };
                    return next.apply(input)
                            .doOnNext(
                                    event -> {
                                        if (event instanceof TextBlockDeltaEvent text
                                                && text.getDelta() != null) {
                                            if (response.length() + text.getDelta().length()
                                                    > 2 * 1024 * 1024)
                                                throw new IllegalStateException("EVIDENCE_LIMIT");
                                            response.append(text.getDelta());
                                        }
                                        if (event instanceof ModelCallEndEvent end
                                                && end.getUsage() != null) {
                                            payload.put(
                                                    "usage",
                                                    Map.of(
                                                            "promptTokens",
                                                            end.getUsage().getInputTokens(),
                                                            "completionTokens",
                                                            end.getUsage().getOutputTokens()));
                                        }
                                    })
                            .doOnComplete(() -> record.accept("COMPLETED"))
                            .doOnError(error -> record.accept("ERROR"))
                            .doOnCancel(() -> record.accept("CANCELLED"));
                });
    }
}
