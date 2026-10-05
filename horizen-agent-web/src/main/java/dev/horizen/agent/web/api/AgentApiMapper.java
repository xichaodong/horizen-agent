package dev.horizen.agent.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.session.SessionExecutionView;
import dev.horizen.agent.application.session.SessionSummaryView;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.presentation.PresentationRecord;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.ApprovalPresentation;
import dev.horizen.agent.runtime.api.AskUserEventDetails;
import dev.horizen.agent.web.api.artifact.ArtifactApi;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.session.SessionApi;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Map;

/** 将领域和应用值映射为 HTTP 与 SSE 契约。 */
@RequiredArgsConstructor
public final class AgentApiMapper {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** mock网关的状态标记，用于选择当前组件的处理路径。 */
    private final boolean mockGateway;

    /**
     * 产生执行流并返回事件。
     *
     * @param event 当前AgentAPI映射器持有的事件对象，供相应处理步骤使用。
     * @return 本次操作返回的对话事件流事件结果。
     */
    public ChatApi.ChatStreamEvent streamEvent(AgentRuntimeEvent event) {
        String type =
                switch (event.getType()) {
                    case TURN_STARTED -> "turn_start";
                    case MODEL_STARTED -> "phase_start";
                    case THINKING_DELTA -> "thinking_delta";
                    case TEXT_DELTA -> "text_delta";
                    case TOOL_STARTED -> "tool_start";
                    case TOOL_INPUT_DELTA -> "tool_input_delta";
                    case TOOL_OUTPUT_DELTA -> "tool_output_delta";
                    case TOOL_COMPLETED -> "tool_end";
                    case TODO_UPDATED -> "todo_updated";
                    case PRESENTATION_CREATED -> "presentation_created";
                    case ASK_USER_REQUIRED -> "ask_user_required";
                    case ASK_USER_RESOLVED -> "ask_user_resolved";
                    case MODEL_COMPLETED -> "phase_end";
                    case APPROVAL_REQUIRED -> "approval_required";
                    case APPROVAL_RESOLVED -> "approval_resolved";
                    case CONTEXT_COMPACTED -> "context_compacted";
                    case CONTEXT_COMPACTION_FAILED -> "context_compaction_failed";
                    case EXECUTION_NOTICE -> "execution_notice";
                    case SUBAGENT_STARTED -> "subagent_start";
                    case SUBAGENT_RESULT -> "subagent_result";
                    case SUBAGENT_COMPLETED -> "subagent_end";
                    case TURN_COMPLETED -> "done";
                    case TURN_FAILED, TURN_TIMED_OUT -> "error";
                    case TURN_CANCELLED -> "cancelled";
                };
        String title =
                switch (event.getType()) {
                    case TURN_STARTED -> mockGateway ? "使用 Mock 演示数据" : "开始处理";
                    case MODEL_STARTED, MODEL_COMPLETED -> "分析与推理";
                    case THINKING_DELTA -> "思考过程";
                    case TEXT_DELTA -> "生成回复";
                    case TOOL_STARTED, TOOL_INPUT_DELTA, TOOL_OUTPUT_DELTA, TOOL_COMPLETED ->
                            toolTitle(event.getToolName());
                    case TODO_UPDATED -> "更新任务进度";
                    case PRESENTATION_CREATED -> "结构化结果";
                    case ASK_USER_REQUIRED -> "等待用户回答";
                    case ASK_USER_RESOLVED -> "用户已回答";
                    case APPROVAL_REQUIRED -> "等待工具审批";
                    case APPROVAL_RESOLVED -> "工具审批已处理";
                    case CONTEXT_COMPACTED -> "上下文压缩";
                    case CONTEXT_COMPACTION_FAILED -> "上下文压缩失败";
                    case EXECUTION_NOTICE -> "执行异常提示";
                    case SUBAGENT_STARTED -> "子 Agent 开始执行";
                    case SUBAGENT_RESULT -> "子 Agent 返回结果";
                    case SUBAGENT_COMPLETED -> "子 Agent 执行完成";
                    case TURN_COMPLETED -> "最终回复";
                    case TURN_FAILED -> failureTitle(event);
                    case TURN_TIMED_OUT -> "执行超时";
                    case TURN_CANCELLED -> "已取消";
                };
        String text =
                event.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED
                        ? formatReply(event.getText())
                        : (event.getType() == AgentRuntimeEvent.Type.TURN_FAILED
                                                || event.getType()
                                                        == AgentRuntimeEvent.Type.TURN_TIMED_OUT)
                                        && (event.getText() == null || event.getText().isBlank())
                                ? failureText(event)
                                : event.getText();
        Object rawDetails =
                event.getType() == AgentRuntimeEvent.Type.ASK_USER_REQUIRED
                        ? AskUserEventDetails.normalize(event.getDetails())
                        : event.getDetails();
        String details =
                rawDetails == null
                        ? null
                        : rawDetails instanceof String value ? value : json(rawDetails);
        ChatApi.ChatStreamEvent mapped =
                new ChatApi.ChatStreamEvent(
                        type,
                        event.getId(),
                        title,
                        text,
                        event.getStatus(),
                        event.getToolName(),
                        details,
                        event.getDurationMs(),
                        event.getLatencyMs(),
                        event.getSource(),
                        event.getTaskId(),
                        event.getParentSessionId(),
                        event.getAgentId(),
                        event.getDepth());
        mapped.setStreamSequence(event.getStreamSequence());
        return mapped;
    }

    /**
     * 将输入 JSON 解码为本方法声明的目标类型，供当前协议或持久化读取使用。
     *
     * @param payloadJson 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     * @return 本次操作返回的对话事件流事件结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ChatApi.ChatStreamEvent timelineEvent(String payloadJson) {
        try {
            return JSON.readValue(payloadJson, ChatApi.ChatStreamEvent.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("持久化时间线事件格式无效", error);
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param text 面向消息或事件消费者的文本内容。
     * @return 本次操作返回的对话事件流事件结果。
     */
    public ChatApi.ChatStreamEvent sessionError(String text) {
        return new ChatApi.ChatStreamEvent(
                "execution_notice",
                "stream-recovery",
                "实时连接恢复异常",
                text,
                "unknown",
                null,
                null,
                null,
                null);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的产物响应结果。
     */
    ArtifactApi.ArtifactResponse artifact(Artifact value) {
        return new ArtifactApi.ArtifactResponse(
                value.getArtifactId(),
                value.getKind().name(),
                value.getState().name(),
                value.getTitle(),
                value.getMediaType(),
                value.getSizeBytes(),
                value.getParentArtifactId());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的呈现响应结果。
     */
    SessionApi.PresentationResponse presentation(PresentationRecord value) {
        var block = value.getBlock();
        return new SessionApi.PresentationResponse(
                block.getBlockId(),
                value.getTurnId(),
                value.getToolCallId(),
                value.getToolName(),
                block.getType(),
                block.getSchemaVersion(),
                block.getPosition(),
                block.getData(),
                value.getCreatedAt());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的会话摘要响应结果。
     */
    SessionApi.SessionSummaryResponse sessionSummary(SessionSummaryView value) {
        return new SessionApi.SessionSummaryResponse(
                value.getSessionId(),
                value.getTitle(),
                value.isPinned(),
                value.getLatestTurnStatus() == null
                        ? "idle"
                        : value.getLatestTurnStatus().name().toLowerCase(Locale.ROOT),
                value.getLatestTurnId(),
                value.getActiveTurnId(),
                value.getLastMessageAt(),
                value.getCreatedAt(),
                value.getUpdatedAt());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的会话执行响应结果。
     */
    SessionApi.SessionExecutionResponse sessionExecution(SessionExecutionView value) {
        return new SessionApi.SessionExecutionResponse(
                value.getSessionId(),
                value.getTurnId(),
                value.idle() ? "idle" : value.getStatus().name().toLowerCase(Locale.ROOT),
                value.getStartedAt(),
                value.getFinishedAt(),
                value.getFailureCode());
    }

    /**
     * 将输入 JSON 解码为本方法声明的目标类型，供当前协议或持久化读取使用。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的审批呈现结果。
     */
    ApprovalPresentation approvalPresentation(ApprovalRequest request) {
        try {
            if (request.getPresentationJson() != null) {
                var value =
                        JSON.readValue(request.getPresentationJson(), ApprovalPresentation.class);
                if (value != null) return value;
            }
        } catch (JsonProcessingException ignored) {
            // 旧记录仍包含足够信息，可以展示具体操作。
        }
        return ApprovalPresentation.fallback(request.getToolName());
    }

    /**
     * 将输入 JSON 解码为本方法声明的目标类型，供当前协议或持久化读取使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 按返回类型约定组织的结果映射。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Map<String, Object> jsonMap(String value) {
        try {
            return JSON.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("审批工具参数不是有效 JSON", error);
        }
    }

    /**
     * 把当前输入编码为 JSON 文本，供协议输出或持久化保存使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot encode event details", error);
        }
    }

    /**
     * 生成当前操作所需的formatReply文本，供调用方继续处理。
     *
     * @param text 面向消息或事件消费者的文本内容。
     * @return 本次处理生成或读取的文本。
     */
    public String formatReply(String text) {
        if (!mockGateway || text.startsWith("【Mock 演示数据】")) return text;
        return "【Mock 演示数据】\n\n" + text;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次操作返回的API异常结果。
     */
    public static ApiException apiError(ApplicationError error) {
        HttpStatus status =
                switch (error.getCode()) {
                    case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case CONFLICT -> HttpStatus.CONFLICT;
                    case EXPIRED -> HttpStatus.GONE;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        return new ApiException(status, error.getMessage());
    }

    /**
     * 检查userVisible对应的条件，供调用方选择后续处理分支。
     *
     * @param event 当前AgentAPI映射器持有的事件对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public static boolean userVisible(AgentRuntimeEvent event) {
        return event.getType() != AgentRuntimeEvent.Type.THINKING_DELTA
                && event.getType() != AgentRuntimeEvent.Type.MODEL_STARTED
                && event.getType() != AgentRuntimeEvent.Type.MODEL_COMPLETED;
    }

    /**
     * 生成当前操作所需的failureTitle文本，供调用方继续处理。
     *
     * @param event 当前AgentAPI映射器持有的事件对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String failureTitle(AgentRuntimeEvent event) {
        return switch (errorCode(event)) {
            case "EXECUTOR_LOST" -> "服务异常中断";
            case "MODEL_FAILED" -> "模型调用失败";
            case "TOOL_FAILED" -> "工具执行失败";
            case "TURN_TIMEOUT", "TURN_DEADLINE_EXCEEDED", "HOST_TIMEOUT" -> "执行超时";
            case "MAX_ITERATIONS_REACHED" -> "达到执行限制";
            default -> "执行失败";
        };
    }

    /**
     * 生成当前操作所需的failureText文本，供调用方继续处理。
     *
     * @param event 当前AgentAPI映射器持有的事件对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String failureText(AgentRuntimeEvent event) {
        return switch (errorCode(event)) {
            case "EXECUTOR_LOST" -> "执行服务异常退出，本轮已停止。你可以重新执行这条请求。";
            case "MODEL_FAILED" -> "模型调用未能完成，本轮已停止。";
            case "TOOL_FAILED" -> "工具执行未能完成，本轮已停止。";
            case "TURN_TIMEOUT", "TURN_DEADLINE_EXCEEDED", "HOST_TIMEOUT" -> "本轮执行超过时间限制，已停止。";
            case "MAX_ITERATIONS_REACHED" -> "本轮已达到执行次数限制，尚未确认任务完成。";
            default -> "Agent 执行失败，本轮已停止。";
        };
    }

    /**
     * 生成当前操作所需的errorCode文本，供调用方继续处理。
     *
     * @param event 当前AgentAPI映射器持有的事件对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String errorCode(AgentRuntimeEvent event) {
        if (event.getDetails() instanceof Map<?, ?> details) {
            Object code = details.get("errorCode");
            if (code != null && !code.toString().isBlank()) return code.toString();
        }
        return "";
    }

    /**
     * 生成当前操作所需的toolTitle文本，供调用方继续处理。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     */
    private static String toolTitle(String name) {
        if (name == null) return "调用工具";
        return switch (name) {
            case "load_skill_through_path" -> "读取 Skill";
            case "todo_write" -> "更新任务进度";
            case "tool_gateway_list" -> "读取工具目录";
            case "tool_gateway_invoke" -> "调用业务工具";
            default -> "调用 " + name;
        };
    }
}
