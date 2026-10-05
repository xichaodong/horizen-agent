package dev.horizen.agent.observability.horizen;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单一职责的追踪采集器，由单次调用独占。 */
final class ModelCallCapture {
    /** 本组件使用的 {@code HorizenTraceRun} 状态或依赖，用于 run 的处理。 */
    private final HorizenTraceRun run;

    /** 当前模型或工具操作正在记录的 Span。 */
    final SpanDraft span;

    /** 模型服务识别的模型名称，脚本模式使用对应的演示名称。 */
    final String modelName;

    /** 面向消息或事件消费者的文本内容。 */
    final StringBuilder text = new StringBuilder();

    /** 工具调用集合的索引映射，供按键查找或归并当前组件的数据。 */
    final Map<String, ToolCallOutput> toolCalls = new LinkedHashMap<>();

    /** 结束的原子状态，供并发更新与统计读取使用。 */
    final AtomicBoolean finished = new AtomicBoolean();

    /** 模型提供方报告的 token 或调用用量，不按消息长度伪造实际用量。 */
    ChatUsage usage;

    /**
     * 创建模型调用采集，初始化该组件所需的状态、配置或依赖。
     *
     * @param run 当前模型调用采集持有的运行对象，供相应处理步骤使用。
     * @param spanId 当前观测操作的 Span 标识，供追踪父子操作关系。
     * @param input 本次处理的输入。
     */
    ModelCallCapture(HorizenTraceRun run, String spanId, ModelCallInput input) {
        this(run, spanId, input, "llm.call." + run.modelIndex.get(), "reasoning");
    }

    /**
     * 创建模型调用采集，初始化该组件所需的状态、配置或依赖。
     *
     * @param run 当前模型调用采集持有的运行对象，供相应处理步骤使用。
     * @param spanId 当前观测操作的 Span 标识，供追踪父子操作关系。
     * @param input 本次处理的输入。
     * @param spanName 当前模型调用采集使用的Span名称，供其处理与状态记录使用。
     * @param operation 当前模型调用采集使用的操作，供其处理与状态记录使用。
     */
    ModelCallCapture(
            HorizenTraceRun run,
            String spanId,
            ModelCallInput input,
            String spanName,
            String operation) {
        this.run = run;
        modelName =
                HorizenTraceRun.textOr(
                        input.options() == null ? null : input.options().getModelName(),
                        input.model().getModelName());
        span =
                new SpanDraft(
                        spanId,
                        run.rootSpanId,
                        "LLM",
                        spanName,
                        System.currentTimeMillis(),
                        modelName,
                        run.modelInput(input),
                        Map.of("toolCount", input.tools().size(), "operation", operation));
        span.provider = input.model().getClass().getName();
    }

    /**
     * 接收并处理模型调用采集。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param event 当前模型调用采集持有的事件对象，供相应处理步骤使用。
     */
    void accept(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta && run.config.isCaptureContent()) {
            text.append(delta.getDelta());
        } else if (event instanceof ToolCallStartEvent start) {
            synchronized (run) {
                run.toolParentSpanIds.put(start.getToolCallId(), span.spanId);
            }
            toolCalls.putIfAbsent(
                    start.getToolCallId(),
                    new ToolCallOutput(run, start.getToolCallId(), start.getToolCallName()));
        } else if (event instanceof ToolCallDeltaEvent delta) {
            synchronized (run) {
                run.toolParentSpanIds.putIfAbsent(delta.getToolCallId(), span.spanId);
            }
            toolCalls
                    .computeIfAbsent(
                            delta.getToolCallId(),
                            ignored ->
                                    new ToolCallOutput(
                                            run, delta.getToolCallId(), delta.getToolCallName()))
                    .arguments
                    .append(delta.getDelta());
        } else if (event instanceof ModelCallEndEvent end) {
            usage = end.getUsage();
        }
    }

    /**
     * 接收并处理模型调用采集。
     *
     * @param response 当前操作得到的响应。
     */
    void accept(ChatResponse response) {
        if (response == null) {
            return;
        }
        if (run.config.isCaptureContent() && response.getContent() != null) {
            response.getContent().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .filter(Objects::nonNull)
                    .forEach(text::append);
        }
        if (response.getUsage() != null) {
            usage = response.getUsage();
        }
    }

    /**
     * 完成模型调用采集。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     */
    void complete() {
        if (!finished.compareAndSet(false, true)) return;
        Map<String, Object> output = new LinkedHashMap<>();
        if (run.config.isCaptureContent()) output.put("content", text.toString());
        else output.put("contentCaptured", false);
        output.put("toolCalls", toolCalls.values().stream().map(ToolCallOutput::view).toList());
        synchronized (run) {
            span.finish(
                    "OK", System.currentTimeMillis(), output, null, HorizenTraceRun.usage(usage));
        }
        run.publish();
    }

    /**
     * 收敛失败的模型调用采集。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     */
    void fail(Throwable error) {
        if (!finished.compareAndSet(false, true)) return;
        synchronized (run) {
            span.finish(
                    "ERROR",
                    System.currentTimeMillis(),
                    null,
                    run.errorView(error),
                    HorizenTraceRun.usage(usage));
        }
        run.publish();
    }

    /**
     * 取消模型调用采集。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     */
    void cancel() {
        if (!finished.compareAndSet(false, true)) return;
        synchronized (run) {
            span.finish(
                    "CANCELLED",
                    System.currentTimeMillis(),
                    null,
                    null,
                    HorizenTraceRun.usage(usage));
        }
        run.publish();
    }
}
