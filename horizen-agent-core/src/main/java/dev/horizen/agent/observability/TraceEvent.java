package dev.horizen.agent.observability;

import lombok.Data;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 带版本的事件信封；各事件的专有属性仍由事件生产方定义。 */
@Data
public class TraceEvent {
    /** Schema的版本，供兼容或并发检查使用。 */
    private int schemaVersion;

    /** 当前事件或观测数据记录的时间。 */
    private String timestamp;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private String turnId;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private String sessionId;

    /** 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。 */
    private String traceId;

    /** 当前观测操作的 Span 标识，供追踪父子操作关系。 */
    private String spanId;

    /** 父级 Span 标识，用于把本操作挂到所属执行链路。 */
    private String parentSpanId;

    /** 本对象的协议类别，用于选择对应的解析或呈现规则。 */
    private String type;

    /** 当前事件或对象携带的扩展属性，按所属协议解释。 */
    private Map<String, Object> attributes;

    /**
     * 创建Trace事件，初始化该组件所需的状态、配置或依赖。
     *
     * @param schemaVersion Schema的版本，供兼容或并发检查使用。
     * @param timestamp 当前Trace事件使用的时间戳，供其处理与状态记录使用。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param traceId 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
     * @param spanId 当前观测操作的 Span 标识，供追踪父子操作关系。
     * @param parentSpanId 父级 Span 标识，用于把本操作挂到所属执行链路。
     * @param type 当前操作使用的目标类型或类别。
     * @param attributes 当前事件或对象携带的扩展属性，按所属协议解释。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
        "schemaVersion",
        "timestamp",
        "turnId",
        "sessionId",
        "traceId",
        "spanId",
        "parentSpanId",
        "type",
        "attributes"
    })
    public TraceEvent(
            int schemaVersion,
            String timestamp,
            String turnId,
            String sessionId,
            String traceId,
            String spanId,
            String parentSpanId,
            String type,
            Map<String, Object> attributes) {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException(
                    "Unsupported trace schema version: " + schemaVersion);
        }
        requireText(timestamp, "timestamp");
        Instant.parse(timestamp);
        requireText(turnId, "turnId");
        requireText(sessionId, "sessionId");
        requireText(traceId, "traceId");
        requireText(spanId, "spanId");
        requireText(type, "type");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));

        this.schemaVersion = schemaVersion;
        this.timestamp = timestamp;
        this.turnId = turnId;
        this.sessionId = sessionId;
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.type = type;
        this.attributes = attributes;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param traceId 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
     * @param spanId 当前观测操作的 Span 标识，供追踪父子操作关系。
     * @param parentSpanId 父级 Span 标识，用于把本操作挂到所属执行链路。
     * @param type 当前操作使用的目标类型或类别。
     * @param attributes 当前事件或对象携带的扩展属性，按所属协议解释。
     * @return 本次操作返回的Trace事件结果。
     */
    public static TraceEvent now(
            String turnId,
            String sessionId,
            String traceId,
            String spanId,
            String parentSpanId,
            String type,
            Map<String, Object> attributes) {
        return new TraceEvent(
                2,
                Instant.now().toString(),
                turnId,
                sessionId,
                traceId,
                spanId,
                parentSpanId,
                type,
                attributes);
    }

    /**
     * 取得并校验文本。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name 需要定位或处理的名称。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
