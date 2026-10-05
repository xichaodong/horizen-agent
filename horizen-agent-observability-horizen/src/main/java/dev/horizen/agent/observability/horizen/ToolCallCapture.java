package dev.horizen.agent.observability.horizen;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单一职责的追踪采集器，由单次调用独占。 */
final class ToolCallCapture {
    /** 本组件使用的 {@code HorizenTraceRun} 状态或依赖，用于 run 的处理。 */
    private final HorizenTraceRun run;

    /** 当前模型或工具操作正在记录的 Span。 */
    final SpanDraft span;

    /** 本次工具调用的原始调用信息。 */
    final ToolUseBlock call;

    /** 面向消息或事件消费者的文本内容。 */
    final StringBuilder text = new StringBuilder();

    /** 数据的有序集合，保留当前组件处理或协议输出所需的顺序。 */
    final List<Object> data = new ArrayList<>();

    /** 结束的原子状态，供并发更新与统计读取使用。 */
    final AtomicBoolean finished = new AtomicBoolean();

    /** 当前工具结果的状态分类，供观测终态写入使用。 */
    ToolResultState resultState;

    /**
     * 创建工具调用采集，初始化该组件所需的状态、配置或依赖。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param run 当前工具调用采集持有的运行对象，供相应处理步骤使用。
     * @param call 当前工具调用采集持有的调用对象，供相应处理步骤使用。
     */
    ToolCallCapture(HorizenTraceRun run, ToolUseBlock call) {
        this.run = run;
        this.call = call;
        String parentSpanId;
        synchronized (run) {
            parentSpanId = run.toolParentSpanIds.getOrDefault(call.getId(), run.rootSpanId);
        }
        this.span =
                new SpanDraft(
                        HorizenTraceRun.randomHex(8),
                        parentSpanId,
                        "TOOL",
                        call.getName(),
                        System.currentTimeMillis(),
                        null,
                        run.toolCallInput(call),
                        Map.of("toolCallId", call.getId(), "toolName", call.getName()));
    }

    /**
     * 接收并处理工具调用采集。
     *
     * @param event 当前工具调用采集持有的事件对象，供相应处理步骤使用。
     */
    void accept(AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent delta && run.config.isCaptureContent()) {
            text.append(delta.getDelta());
        } else if (event instanceof ToolResultDataDeltaEvent delta
                && run.config.isCaptureContent()) {
            data.add(run.sanitizer.sanitize(delta.getData()));
        } else if (event instanceof ToolResultEndEvent end) {
            resultState = end.getState();
        }
    }

    /**
     * 完成工具调用采集。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     */
    void complete() {
        if (!finished.compareAndSet(false, true)) return;
        String toolStatus = resultState == null ? "success" : resultState.getValue();
        String spanStatus =
                resultState == ToolResultState.RUNNING
                        ? "RUNNING"
                        : resultState == null || resultState == ToolResultState.SUCCESS
                                ? "OK"
                                : "ERROR";
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", toolStatus);
        if (run.config.isCaptureContent()) {
            output.put("text", text.toString());
            if (!data.isEmpty()) output.put("data", List.copyOf(data));
        } else {
            output.put("contentCaptured", false);
        }
        synchronized (run) {
            span.finish(
                    spanStatus,
                    System.currentTimeMillis(),
                    output,
                    spanStatus.equals("ERROR") ? Map.of("toolState", toolStatus) : null,
                    null);
            run.addEvent(
                    span.spanId,
                    "TOOL_RESULT",
                    call.getName(),
                    "TOOL",
                    Map.of(
                            "toolCallId",
                            call.getId(),
                            "toolName",
                            call.getName(),
                            "status",
                            toolStatus,
                            "result",
                            output));
        }
        run.publish();
    }

    /**
     * 收敛失败的工具调用采集。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     */
    void fail(Throwable error) {
        if (!finished.compareAndSet(false, true)) return;
        synchronized (run) {
            span.finish("ERROR", System.currentTimeMillis(), null, run.errorView(error), null);
        }
        run.publish();
    }

    /**
     * 取消工具调用采集。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     */
    void cancel() {
        if (!finished.compareAndSet(false, true)) return;
        synchronized (run) {
            span.finish("CANCELLED", System.currentTimeMillis(), null, null, null);
        }
        run.publish();
    }
}
