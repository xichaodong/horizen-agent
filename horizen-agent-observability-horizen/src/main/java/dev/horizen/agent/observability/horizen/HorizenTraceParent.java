package dev.horizen.agent.observability.horizen;

import lombok.Value;

/**
 * 启动子调用的具体委派工具调用坐标。
 */
@Value
final class HorizenTraceParent {
    /**
     * 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
     */
    String traceId;

    /**
     * 当前观测操作的 Span 标识，供追踪父子操作关系。
     */
    String spanId;
}
