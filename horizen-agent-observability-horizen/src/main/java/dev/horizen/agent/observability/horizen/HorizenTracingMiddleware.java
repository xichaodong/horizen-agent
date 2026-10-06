package dev.horizen.agent.observability.horizen;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.observability.TraceDataSanitizer;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;

import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.function.Function;

/**
 * 将 AgentScope 模型和工具调用边界转换为 Horizen 追踪契约 v1 的数据。
 */
public final class HorizenTracingMiddleware implements MiddlewareBase {
    /**
     * REACTOR工作状态键的固定取值，用于相应策略和边界判断。
     */
    static final String REACTOR_STATE_KEY = HorizenTracingMiddleware.class.getName() + ".run";

    /**
     * 当前组件的配置与策略参数。
     */
    private final HorizenTraceConfig config;

    /**
     * 本组件写入事件或观测数据的接收端，具体协议由声明类型确定。
     */
    private final HorizenTraceBatchSink sink;

    /**
     * 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    private final ObjectMapper mapper;

    /**
     * 按已知敏感字段名清理观测负载的脱敏器，不识别任意自由文本中的秘密。
     */
    private final TraceDataSanitizer sanitizer;

    /**
     * 创建Horizen观测中间件，初始化该组件所需的状态、配置或依赖。
     *
     * @param config 当前组件的配置与策略参数。
     * @param sink   当前Horizen观测中间件持有的上报端对象，供相应处理步骤使用。
     */
    public HorizenTracingMiddleware(HorizenTraceConfig config, HorizenTraceBatchSink sink) {
        this.config = Objects.requireNonNull(config, "config");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.mapper = JsonUtils.newMapper();
        this.sanitizer = new TraceDataSanitizer(mapper);
    }

    /**
     * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
     *
     * @return 中间件执行顺序值。
     */
    @Override
    public int order() {
        return 1000;
    }

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
        return Flux.defer(
                () -> {
                    if (agent instanceof ReActAgent react) HorizenDelegationTool.install(react);
                    HorizenTraceRun run =
                            HorizenTraceRun.start(
                                    config, sink, sanitizer, agent, context, input.msgs());
                    return Flux.defer(() -> next.apply(input))
                            .doOnNext(run::observeAgentEvent)
                            .doOnComplete(run::complete)
                            .doOnError(run::fail)
                            .doOnCancel(run::cancel)
                            .contextWrite(
                                    reactorContext -> reactorContext.put(REACTOR_STATE_KEY, run));
                });
    }

    /**
     * 响应模型调用。
     *
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input   本次处理的输入。
     * @param next    将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                view -> {
                    HorizenTraceRun run = view.getOrDefault(REACTOR_STATE_KEY, null);
                    if (run == null) {
                        return next.apply(input);
                    }
                    return run.traceModelCall(input, next);
                });
    }

    /**
     * 响应Acting。
     *
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input   本次处理的输入。
     * @param next    将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                view -> {
                    HorizenTraceRun run = view.getOrDefault(REACTOR_STATE_KEY, null);
                    if (run == null) {
                        return next.apply(input);
                    }
                    return run.traceTools(input, next);
                });
    }
}
