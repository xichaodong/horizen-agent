package dev.horizen.agent.observability;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Turn 级 AgentScope 事件观察器；只记录事件，不改变执行过程，也不持有会话状态。
 */
public final class AgentEventObserver implements Consumer<AgentEvent> {
    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private final String turnId;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private final String sessionId;

    /**
     * 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
     */
    private final String traceId;

    /**
     * 当前观测操作的 Span 标识，供追踪父子操作关系。
     */
    private final String spanId;

    /**
     * 本组件写入事件或观测数据的接收端，具体协议由声明类型确定。
     */
    private final TraceSink sink;

    /**
     * 采集文本的状态标记，用于选择当前组件的处理路径。
     */
    private final boolean captureText;

    /**
     * 创建Agent事件观察器，初始化该组件所需的状态、配置或依赖。
     *
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param sink      当前Agent事件观察器持有的上报端对象，供相应处理步骤使用。
     */
    public AgentEventObserver(String turnId, String sessionId, TraceSink sink) {
        this(turnId, sessionId, sink, false);
    }

    /**
     * 只有数据已获准发送到目标 Trace 存储时，才应启用正文采集。
     */
    public AgentEventObserver(
            String turnId, String sessionId, TraceSink sink, boolean captureText) {
        this.turnId = requireText(turnId, "turnId");
        this.sessionId = requireText(sessionId, "sessionId");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.captureText = captureText;
        this.traceId = UUID.randomUUID().toString().replace("-", "");
        this.spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 接收并处理Agent事件观察器。
     *
     * @param event 当前Agent事件观察器持有的事件对象，供相应处理步骤使用。
     */
    @Override
    public void accept(AgentEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentEventId", event.getId());
        if (event instanceof ModelCallEndEvent end && end.getUsage() != null) {
            data.put("inputTokens", end.getUsage().getInputTokens());
            data.put("outputTokens", end.getUsage().getOutputTokens());
        }
        if (event instanceof ToolResultEndEvent end) {
            putIfPresent(data, "toolCallId", end.getToolCallId());
            putIfPresent(data, "toolName", end.getToolCallName());
            putIfPresent(data, "toolState", end.getState());
        }
        if (event instanceof AgentResultEvent result && result.getResult() != null) {
            putIfPresent(data, "generateReason", result.getResult().getGenerateReason());
            if (captureText) {
                putIfPresent(data, "text", result.getResult().getTextContent());
            }
        }
        if (captureText && event instanceof TextBlockDeltaEvent delta) {
            putIfPresent(data, "text", delta.getDelta());
        }
        emit(event.getType().name(), data);
    }

    /**
     * 通过 doOnError 接入，只记录异常类型，不记录可能包含敏感信息的异常正文。
     */
    public void onError(Throwable error) {
        emit("EXECUTION_ERROR", Map.of("errorType", error.getClass().getName()));
    }

    /**
     * 响应取消。
     */
    public void onCancel() {
        emit("EXECUTION_CANCELLED", Map.of());
    }

    /**
     * 发送Agent事件观察器。
     *
     * @param type       当前操作使用的目标类型或类别。
     * @param attributes 当前事件或对象携带的扩展属性，按所属协议解释。
     */
    private void emit(String type, Map<String, Object> attributes) {
        sink.record(TraceEvent.now(turnId, sessionId, traceId, spanId, null, type, attributes));
    }

    /**
     * 写入条件存在。
     *
     * @param target 本次转换、状态更新或内容写入的目标。
     * @param key    当前对象的查找或写入键。
     * @param value  待校验、转换或保存的原始值。
     */
    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 取得并校验文本。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name  需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
