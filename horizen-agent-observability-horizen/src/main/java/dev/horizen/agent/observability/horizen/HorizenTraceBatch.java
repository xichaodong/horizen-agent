package dev.horizen.agent.observability.horizen;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.beans.ConstructorProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * 发往 {@code /api/v1/sdk/traces/batch} 的 Horizen 追踪契约 v1 负载。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class HorizenTraceBatch {
    /**
     * 契约的版本，供兼容或并发检查使用。
     */
    private int contractVersion;

    /**
     * 工作区或发布所属 Project 的标识，参与资源归属校验。
     */
    private long projectId;

    /**
     * 当前事件、内容或执行的来源，供追踪生成关系与执行层级使用。
     */
    private String source;

    /**
     * 上报数据所声明的 SDK 名称，供远端识别接入来源。
     */
    private String sdkName;

    /**
     * SDK的版本，供兼容或并发检查使用。
     */
    private String sdkVersion;

    /**
     * 当前运行或批次包关联的 Trace 对象。
     */
    private Trace trace;

    /**
     * Span集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    private List<Span> spans;

    /**
     * 当前执行或历史事件集合，供持久化、回放与观测使用。
     */
    private List<Event> events;

    /**
     * 创建HorizenTrace批次，初始化该组件所需的状态、配置或依赖。
     *
     * @param contractVersion 契约的版本，供兼容或并发检查使用。
     * @param projectId       工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param source          待解析或转换的来源对象。
     * @param sdkName         当前HorizenTrace批次使用的SDK名称，供其处理与状态记录使用。
     * @param sdkVersion      SDK的版本，供兼容或并发检查使用。
     * @param trace           当前HorizenTrace批次持有的Trace对象，供相应处理步骤使用。
     * @param spans           Span集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param events          当前执行或历史事件集合，供持久化、回放与观测使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
            "contractVersion",
            "projectId",
            "source",
            "sdkName",
            "sdkVersion",
            "trace",
            "spans",
            "events"
    })
    public HorizenTraceBatch(
            int contractVersion,
            long projectId,
            String source,
            String sdkName,
            String sdkVersion,
            Trace trace,
            List<Span> spans,
            List<Event> events) {
        if (contractVersion != 1) {
            throw new IllegalArgumentException(
                    "Unsupported Horizen trace contract: " + contractVersion);
        }
        spans = List.copyOf(spans == null ? List.of() : spans);
        events = List.copyOf(events == null ? List.of() : events);

        this.contractVersion = contractVersion;
        this.projectId = projectId;
        this.source = source;
        this.sdkName = sdkName;
        this.sdkVersion = sdkVersion;
        this.trace = trace;
        this.spans = spans;
        this.events = events;
    }

    /**
     * 一次 Agent 执行的 Trace 描述，关联会话、状态、耗时与用量。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Trace {
        /**
         * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
         */
        private String turnId;

        /**
         * 调用的标识，用于关联相应记录或执行。
         */
        private String invocationId;

        /**
         * 父级Trace的标识，用于关联相应记录或执行。
         */
        private String parentTraceId;

        /**
         * 父级 Span 标识，用于把本操作挂到所属执行链路。
         */
        private String parentSpanId;

        /**
         * 本次 Agent 调用的来源类别，例如交互执行或评测。
         */
        private String invocationKind;

        /**
         * 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
         */
        private String traceId;

        /**
         * 用例运行的标识，用于关联相应记录或执行。
         */
        private Long caseRunId;

        /**
         * Agent目标的标识，用于关联相应记录或执行。
         */
        private Long agentTargetId;

        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        private String sessionId;

        /**
         * 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
         */
        private String userId;

        /**
         * 当前Trace的名称，用于目录、调用或展示中的识别。
         */
        private String name;

        /**
         * 当前记录或执行的状态，具体取值由所属领域或协议约定。
         */
        private String status;

        /**
         * 开始时间，单位为毫秒。
         */
        private Long startedAtMs;

        /**
         * 已结束时间，单位为毫秒。
         */
        private Long endedAtMs;

        /**
         * 当前操作的输入数据，格式由所属命令、协议或工具定义。
         */
        private Object input;

        /**
         * 当前操作产生的输出数据，供结果转换与交付使用。
         */
        private Object output;

        /**
         * 与当前对象关联的附加元数据，不替代领域状态或授权校验。
         */
        private Object metadata;

        /**
         * 模型提供方报告的累计成本数值；是否可得由实际用量数据决定。
         */
        private BigDecimal totalCost;

        /**
         * 成本数值采用的币种或计价单位。
         */
        private String currency;

        /**
         * 创建Trace，初始化该组件所需的状态、配置或依赖。
         *
         * @param traceId       关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
         * @param caseRunId     用例运行的标识，用于关联相应记录或执行。
         * @param agentTargetId Agent目标的标识，用于关联相应记录或执行。
         * @param sessionId     会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         * @param userId        上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
         * @param name          需要定位或处理的名称。
         * @param status        当前记录或执行的状态，具体取值由所属领域或协议约定。
         * @param startedAtMs   开始时间，单位为毫秒。
         * @param endedAtMs     已结束时间，单位为毫秒。
         * @param input         本次处理的输入。
         * @param output        本次处理产生或填充的输出。
         * @param metadata      与当前对象关联的附加元数据，不替代领域状态或授权校验。
         * @param totalCost     当前Trace持有的总计消耗对象，供相应处理步骤使用。
         * @param currency      当前Trace使用的currency，供其处理与状态记录使用。
         */
        public Trace(
                String traceId,
                Long caseRunId,
                Long agentTargetId,
                String sessionId,
                String userId,
                String name,
                String status,
                Long startedAtMs,
                Long endedAtMs,
                Object input,
                Object output,
                Object metadata,
                BigDecimal totalCost,
                String currency) {
            this(
                    null,
                    null,
                    null,
                    null,
                    null,
                    traceId,
                    caseRunId,
                    agentTargetId,
                    sessionId,
                    userId,
                    name,
                    status,
                    startedAtMs,
                    endedAtMs,
                    input,
                    output,
                    metadata,
                    totalCost,
                    currency);
        }
    }

    /**
     * Trace 下的模型或工具操作 Span，记录关联与执行结果。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Span {
        /**
         * 当前观测操作的 Span 标识，供追踪父子操作关系。
         */
        private String spanId;

        /**
         * 父级 Span 标识，用于把本操作挂到所属执行链路。
         */
        private String parentSpanId;

        /**
         * 当前 Span 的操作类别，用于区分模型与工具调用。
         */
        private String spanType;

        /**
         * 当前Span的名称，用于目录、调用或展示中的识别。
         */
        private String name;

        /**
         * 当前记录或执行的状态，具体取值由所属领域或协议约定。
         */
        private String status;

        /**
         * 开始时间，单位为毫秒。
         */
        private Long startedAtMs;

        /**
         * 已结束时间，单位为毫秒。
         */
        private Long endedAtMs;

        /**
         * 当前执行步骤的耗时，单位为毫秒。
         */
        private Long durationMs;

        /**
         * 当前模型实例或模型标识，按字段声明的类型解释。
         */
        private String model;

        /**
         * 当前协议或执行环境使用的提供方标识。
         */
        private String provider;

        /**
         * 当前操作的输入数据，格式由所属命令、协议或工具定义。
         */
        private Object input;

        /**
         * 当前操作产生的输出数据，供结果转换与交付使用。
         */
        private Object output;

        /**
         * 模型提供方报告的 token 或调用用量，不按消息长度伪造实际用量。
         */
        private Object usage;

        /**
         * 模型提供方报告的本次操作成本。
         */
        private Object cost;

        /**
         * 模型提供方报告的累计成本数值；是否可得由实际用量数据决定。
         */
        private BigDecimal totalCost;

        /**
         * 成本数值采用的币种或计价单位。
         */
        private String currency;

        /**
         * 当前失败信息，供执行收敛或协议错误输出使用。
         */
        private Object error;

        /**
         * 与当前对象关联的附加元数据，不替代领域状态或授权校验。
         */
        private Object metadata;
    }

    /**
     * Trace 或 Span 关联的具体过程事件。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        /**
         * 事件的标识，用于关联相应记录或执行。
         */
        private String eventId;

        /**
         * 当前观测操作的 Span 标识，供追踪父子操作关系。
         */
        private String spanId;

        /**
         * 当前观测或评测事件在其所属序列中的位置。
         */
        private Integer eventIndex;

        /**
         * 当前观测或评测事件的协议类别。
         */
        private String eventType;

        /**
         * 当前事件的名称，用于目录、调用或展示中的识别。
         */
        private String name;

        /**
         * 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
         */
        private String role;

        /**
         * 时间戳，单位为毫秒。
         */
        private Long timestampMs;

        /**
         * 当前事件的结构化负载，与定位标识和事件类别分开保存。
         */
        private Object payload;
    }
}
