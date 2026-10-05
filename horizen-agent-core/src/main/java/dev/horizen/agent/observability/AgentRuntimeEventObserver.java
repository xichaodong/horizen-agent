package dev.horizen.agent.observability;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** 记录通用 Runtime 事件，不依赖 AgentScope 的原生事件模型。 */
public final class AgentRuntimeEventObserver implements Consumer<AgentRuntimeEvent> {
    /** 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。 */
    private final String traceId = UUID.randomUUID().toString().replace("-", "");

    /** 当前观测操作的 Span 标识，供追踪父子操作关系。 */
    private final String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    /** 本组件写入事件或观测数据的接收端，具体协议由声明类型确定。 */
    private final TraceSink sink;

    /** 采集文本的状态标记，用于选择当前组件的处理路径。 */
    private final boolean captureText;

    /**
     * 创建Agent运行时事件观察器，初始化该组件所需的状态、配置或依赖。
     *
     * @param sink 当前Agent运行时事件观察器持有的上报端对象，供相应处理步骤使用。
     */
    public AgentRuntimeEventObserver(TraceSink sink) {
        this(sink, false);
    }

    /**
     * 创建Agent运行时事件观察器，初始化该组件所需的状态、配置或依赖。
     *
     * @param sink 当前Agent运行时事件观察器持有的上报端对象，供相应处理步骤使用。
     * @param captureText 采集文本的状态标记，用于选择当前组件的处理路径。
     */
    public AgentRuntimeEventObserver(TraceSink sink, boolean captureText) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.captureText = captureText;
    }

    /**
     * 接收并处理Agent运行时事件观察器。
     *
     * @param event 当前Agent运行时事件观察器持有的事件对象，供相应处理步骤使用。
     */
    @Override
    public void accept(AgentRuntimeEvent event) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, "eventId", event.getId());
        put(attributes, "status", event.getStatus());
        put(attributes, "toolName", event.getToolName());
        put(attributes, "source", event.getSource());
        put(attributes, "taskId", event.getTaskId());
        put(attributes, "parentSessionId", event.getParentSessionId());
        put(attributes, "agentId", event.getAgentId());
        put(attributes, "depth", event.getDepth());
        if (captureText || event.getType() == AgentRuntimeEvent.Type.MODEL_COMPLETED) {
            put(attributes, "details", event.getDetails());
        }
        if (event.getDetails() instanceof Map<?, ?> details) {
            // 即使关闭负载采集，稳定的诊断字段也保持可观测。
            put(attributes, "errorCode", details.get("errorCode"));
            put(attributes, "stopReason", details.get("stopReason"));
        }
        put(attributes, "durationMs", event.getDurationMs());
        put(attributes, "latencyMs", event.getLatencyMs());
        if (captureText) {
            put(attributes, "text", event.getText());
        }
        sink.record(
                TraceEvent.now(
                        event.getTurnId(),
                        event.getSessionId(),
                        traceId,
                        spanId,
                        null,
                        event.getType().name(),
                        attributes));
    }

    /**
     * 响应错误。
     *
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param error 本次失败的异常，用于分类、传播或诊断。
     */
    public void onError(String turnId, String sessionId, Throwable error) {
        sink.record(
                TraceEvent.now(
                        turnId,
                        sessionId,
                        traceId,
                        spanId,
                        null,
                        "EXECUTION_ERROR",
                        Map.of("errorType", error.getClass().getName())));
    }

    /**
     * 响应取消。
     *
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    public void onCancel(String turnId, String sessionId) {
        sink.record(
                TraceEvent.now(
                        turnId, sessionId, traceId, spanId, null, "EXECUTION_CANCELLED", Map.of()));
    }

    /**
     * 写入Agent运行时事件观察器。
     *
     * @param target 本次转换、状态更新或内容写入的目标。
     * @param key 当前对象的查找或写入键。
     * @param value 待校验、转换或保存的原始值。
     */
    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
