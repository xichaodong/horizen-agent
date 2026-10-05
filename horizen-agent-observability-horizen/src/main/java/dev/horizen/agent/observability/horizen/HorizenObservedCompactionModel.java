package dev.horizen.agent.observability.horizen;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/** 为 AgentScope 内部直接调用的压缩模型补充 Horizen LLM Span。 */
public final class HorizenObservedCompactionModel implements Model {
    /** 被包装的原始实现，由本组件补充隔离、观测或恢复行为。 */
    private final Model delegate;

    /**
     * 创建HorizenObserved压缩模型，初始化该组件所需的状态、配置或依赖。
     *
     * @param delegate 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    public HorizenObservedCompactionModel(Model delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 产生执行流并返回HorizenObserved压缩模型。
     *
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param tools 工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param options 可供当前请求选择的选项或策略集合。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(
                view -> {
                    HorizenTraceRun run =
                            view.getOrDefault(HorizenTracingMiddleware.REACTOR_STATE_KEY, null);
                    if (run == null) {
                        return delegate.stream(messages, tools, options);
                    }
                    return run.traceCompactionModelCall(
                            delegate,
                            messages,
                            tools,
                            options,
                            () -> delegate.stream(messages, tools, options));
                });
    }

    /**
     * 读取模型名称。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /**
     * 判断是否支持原生结构化输出。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    /**
     * 判断是否支持原生结构化输出配置工具集合。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    /**
     * 读取上下文窗口大小。
     *
     * @return 本次操作返回的整数结果。
     */
    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
