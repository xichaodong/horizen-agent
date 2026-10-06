package dev.horizen.agent.runtime.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 执行过程中产生的通用流式事件，不向调用方暴露 AgentScope 事件类型。
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuntimeEvent {
    /**
     * 本对象的协议类别，用于选择对应的解析或呈现规则。
     */
    private Type type;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private String sessionId;

    /**
     * 当前Agent运行时事件的定位标识。
     */
    private String id;

    /**
     * 当前Agent运行时事件的可读标题，供宿主界面展示。
     */
    private String title;

    /**
     * 面向消息或事件消费者的文本内容。
     */
    private String text;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private String status;

    /**
     * 可调用工具的注册名称，须与目录中声明的名称一致。
     */
    private String toolName;

    /**
     * 当前事件或查询结果的补充细节，供状态解释与展示使用。
     */
    private Object details;

    /**
     * 当前执行步骤的耗时，单位为毫秒。
     */
    private Long durationMs;

    /**
     * 当前执行请求的总耗时，单位为毫秒。
     */
    private Long latencyMs;

    /**
     * 当前事件、内容或执行的来源，供追踪生成关系与执行层级使用。
     */
    private String source;

    /**
     * 任务的标识，用于关联相应记录或执行。
     */
    private String taskId;

    /**
     * 父级会话标识，用于关联委派任务与其发起会话。
     */
    private String parentSessionId;

    /**
     * 当前执行 Agent 的标识，用于区分主 Agent 与委派执行者。
     */
    private String agentId;

    /**
     * 当前节点在执行树中的嵌套深度，供宿主分层展示。
     */
    private Integer depth;

    /**
     * 当前 Turn Redis List 的 1-based 事件序号；未持久化的本地事件为空。
     */
    private Long streamSequence;

    /**
     * 兼容新增 Redis 事件游标前调用方式的构造方法。
     */
    public AgentRuntimeEvent(
            Type type,
            String turnId,
            String sessionId,
            String id,
            String title,
            String text,
            String status,
            String toolName,
            Object details,
            Long durationMs,
            Long latencyMs,
            String source,
            String taskId,
            String parentSessionId,
            String agentId,
            Integer depth) {
        this(
                type,
                turnId,
                sessionId,
                id,
                title,
                text,
                status,
                toolName,
                details,
                durationMs,
                latencyMs,
                source,
                taskId,
                parentSessionId,
                agentId,
                depth,
                null);
    }

    /**
     * 兼容未传入执行树来源信息的调用方。
     */
    public AgentRuntimeEvent(
            Type type,
            String turnId,
            String sessionId,
            String id,
            String title,
            String text,
            String status,
            String toolName,
            Object details,
            Long durationMs,
            Long latencyMs) {
        this(
                type,
                turnId,
                sessionId,
                id,
                title,
                text,
                status,
                toolName,
                details,
                durationMs,
                latencyMs,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 对宿主暴露的执行事件类型，保持与 AgentScope 原生事件解耦。
     */
    public enum Type {
        /**
         * 一次宿主执行已开始。
         */
        TURN_STARTED,
        /**
         * 一次模型请求开始。
         */
        MODEL_STARTED,
        /**
         * 模型思考文本的增量片段。
         */
        THINKING_DELTA,
        /**
         * 模型回复文本的增量片段。
         */
        TEXT_DELTA,
        /**
         * 一次工具调用开始。
         */
        TOOL_STARTED,
        /**
         * 工具参数逐步接收的增量片段。
         */
        TOOL_INPUT_DELTA,
        /**
         * 工具输出的增量片段。
         */
        TOOL_OUTPUT_DELTA,
        /**
         * 一次工具调用已结束，成功与否仍以工具结果状态为准。
         */
        TOOL_COMPLETED,
        /**
         * 执行任务清单或任务进度已更新。
         */
        TODO_UPDATED,
        /**
         * 生成新的结构化呈现块。
         */
        PRESENTATION_CREATED,
        /**
         * 执行需要用户回答澄清问题。
         */
        ASK_USER_REQUIRED,
        /**
         * 原澄清请求已经得到处理。
         */
        ASK_USER_RESOLVED,
        /**
         * 一次模型调用已结束，不单独代表整个执行成功。
         */
        MODEL_COMPLETED,
        /**
         * 原工具调用需要审批决定。
         */
        APPROVAL_REQUIRED,
        /**
         * 原审批请求已经得到处理。
         */
        APPROVAL_RESOLVED,
        /**
         * 模型工作上下文已完成压缩。
         */
        CONTEXT_COMPACTED,
        /**
         * 上下文压缩未成功完成。
         */
        CONTEXT_COMPACTION_FAILED,
        /**
         * 执行过程中供宿主展示的非终态说明。
         */
        EXECUTION_NOTICE,
        /**
         * 委派的子 Agent 已开始工作。
         */
        SUBAGENT_STARTED,
        /**
         * 子 Agent 产生可以转交主执行的结果。
         */
        SUBAGENT_RESULT,
        /**
         * 子 Agent 当前执行段已结束。
         */
        SUBAGENT_COMPLETED,
        /**
         * 当前宿主执行成功完成。
         */
        TURN_COMPLETED,
        /**
         * 当前宿主执行失败。
         */
        TURN_FAILED,
        /**
         * 当前宿主执行已取消。
         */
        TURN_CANCELLED,
        /**
         * 当前宿主执行已超时。
         */
        TURN_TIMED_OUT
    }

    /**
     * 生成当前对象的诊断文本。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String toString() {
        return "AgentRuntimeEvent[type="
                + type
                + ", turnId="
                + turnId
                + ", sessionId="
                + sessionId
                + ", id="
                + id
                + ", status="
                + status
                + ", toolName="
                + toolName
                + ", source="
                + source
                + ", taskId="
                + taskId
                + ", agentId="
                + agentId
                + ", depth="
                + depth
                + ", textLength="
                + (text == null ? 0 : text.length())
                + ", detailsType="
                + (details == null ? null : details.getClass().getName())
                + ", durationMs="
                + durationMs
                + ", latencyMs="
                + latencyMs
                + "]";
    }
}
