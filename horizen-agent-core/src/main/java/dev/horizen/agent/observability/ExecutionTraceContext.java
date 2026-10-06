package dev.horizen.agent.observability;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用级关联信息；复制的上下文共享工具坐标，不共享执行身份。
 */
@RequiredArgsConstructor
@Getter
public final class ExecutionTraceContext {
    /**
     * 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
     */
    private final String traceId;

    /**
     * 调用的标识，用于关联相应记录或执行。
     */
    private final String invocationId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private final String turnId;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private final String sessionId;

    /**
     * 运行时会话的标识，用于关联相应记录或执行。
     */
    private final String runtimeSessionId;

    /**
     * 根Span的标识，用于关联相应记录或执行。
     */
    private final String rootSpanId;

    /**
     * 工具Span集合的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<String, String> toolSpans = new ConcurrentHashMap<>();

    /**
     * 注册工具。
     *
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param spanId     当前观测操作的 Span 标识，供追踪父子操作关系。
     */
    public void registerTool(String toolCallId, String spanId) {
        if (toolCallId != null) toolSpans.put(toolCallId, spanId);
    }

    /**
     * 生成当前操作所需的toolSpan文本，供调用方继续处理。
     *
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @return 本次处理生成或读取的文本。
     */
    public String toolSpan(String toolCallId) {
        return toolCallId == null ? rootSpanId : toolSpans.getOrDefault(toolCallId, rootSpanId);
    }

    /**
     * 计算或取得本方法声明的结果，供当前ExecutionTraceContext处理步骤使用。
     *
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @return 按返回类型约定组织的结果映射。
     */
    public Map<String, String> toolHeaders(String toolCallId) {
        return Map.of(
                "X-Horizen-Trace-Id",
                traceId,
                "X-Horizen-Span-Id",
                toolSpan(toolCallId),
                "X-Horizen-Turn-Id",
                turnId,
                "X-Horizen-Invocation-Id",
                invocationId);
    }
}
