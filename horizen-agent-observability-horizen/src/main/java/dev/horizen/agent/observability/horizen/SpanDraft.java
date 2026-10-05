package dev.horizen.agent.observability.horizen;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** 单一职责的追踪采集器，由单次调用独占。 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class SpanDraft {
    /** 当前观测操作的 Span 标识，供追踪父子操作关系。 */
    final String spanId;

    /** 父级 Span 标识，用于把本操作挂到所属执行链路。 */
    final String parentSpanId;

    /** 本对象的协议类别，用于选择对应的解析或呈现规则。 */
    final String type;

    /** 当前Span草稿的名称，用于目录、调用或展示中的识别。 */
    final String name;

    /** 开始时间，单位为毫秒。 */
    final long startedAtMs;

    /** 当前模型实例或模型标识，按字段声明的类型解释。 */
    final String model;

    /** 当前操作的输入数据，格式由所属命令、协议或工具定义。 */
    final Object input;

    /** 与当前对象关联的附加元数据，不替代领域状态或授权校验。 */
    final Object metadata;

    /** 当前协议或执行环境使用的提供方标识。 */
    String provider;

    /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
    String status = "RUNNING";

    /** 已结束时间，单位为毫秒。 */
    Long endedAtMs;

    /** 当前操作产生的输出数据，供结果转换与交付使用。 */
    Object output;

    /** 模型提供方报告的 token 或调用用量，不按消息长度伪造实际用量。 */
    Object usage;

    /** 当前失败信息，供执行收敛或协议错误输出使用。 */
    Object error;

    /**
     * 收敛Span草稿。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param endedAtMs 已结束时间，单位为毫秒。
     * @param output 本次处理产生或填充的输出。
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @param usage 当前Span草稿持有的用量对象，供相应处理步骤使用。
     */
    void finish(String status, long endedAtMs, Object output, Object error, Object usage) {
        this.status = status;
        this.endedAtMs = endedAtMs;
        this.output = output;
        this.error = error;
        this.usage = usage;
    }

    /**
     * 读取快照中的Span草稿。
     *
     * @return 本次操作返回的Span结果。
     */
    HorizenTraceBatch.Span snapshot() {
        return new HorizenTraceBatch.Span(
                spanId,
                parentSpanId,
                type,
                name,
                status,
                startedAtMs,
                endedAtMs,
                endedAtMs == null ? null : Math.max(0, endedAtMs - startedAtMs),
                model,
                provider,
                input,
                output,
                usage,
                null,
                null,
                null,
                error,
                metadata);
    }
}
