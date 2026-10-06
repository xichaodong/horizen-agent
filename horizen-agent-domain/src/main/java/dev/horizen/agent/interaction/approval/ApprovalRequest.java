package dev.horizen.agent.interaction.approval;

import dev.horizen.agent.common.validation.Preconditions;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;

/**
 * AgentScope 请求人工确认时保存的产品审批记录。
 */
@Getter
@EqualsAndHashCode
@ToString
public class ApprovalRequest {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private final String ownerKey;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private final String sessionId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private final String turnId;

    /**
     * 待确认操作的记录标识，提交决定时用它定位原审批。
     */
    private final String approvalId;

    /**
     * 请求回复的标识，用于关联相应记录或执行。
     */
    private final String requestReplyId;

    /**
     * 一次工具调用的标识，用于配对参数、结果和审批事件。
     */
    private final String toolCallId;

    /**
     * 可调用工具的注册名称，须与目录中声明的名称一致。
     */
    private final String toolName;

    /**
     * 原工具调用的序列化调用内容，供暂停后恢复使用。
     */
    private final String toolContent;

    /**
     * 工具参数的 JSON 表示，供持久化或协议转换使用。
     */
    private final String toolArgumentsJson;

    /**
     * 结构化呈现内容的持久化 JSON 表示。
     */
    private final String presentationJson;

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的审批请求结果。
     */
    public ApprovalRequest withPresentationJson(String value) {
        return new ApprovalRequest(
                ownerKey,
                sessionId,
                turnId,
                approvalId,
                requestReplyId,
                toolCallId,
                toolName,
                toolContent,
                toolArgumentsJson,
                value,
                status,
                requestedBy,
                expiresAt,
                decidedBy,
                decidedAt,
                createdAt,
                updatedAt,
                version);
    }

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private final ApprovalStatus status;

    /**
     * 发起原交互请求的操作方标识。
     */
    private final String requestedBy;

    /**
     * 当前记录或授权的失效时间，用于过期检查。
     */
    private final Instant expiresAt;

    /**
     * 提交当前交互决定的操作方标识。
     */
    private final String decidedBy;

    /**
     * 已决定的时间，用于记录对应生命周期节点。
     */
    private final Instant decidedAt;

    /**
     * 当前记录的创建时间。
     */
    private final Instant createdAt;

    /**
     * 当前记录最近一次更新的时间。
     */
    private final Instant updatedAt;

    /**
     * 记录版本，用于乐观并发控制或区分协议版本。
     */
    private final long version;

    /**
     * 创建审批请求，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey          宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId         会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId            单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param approvalId        待确认操作的记录标识，提交决定时用它定位原审批。
     * @param requestReplyId    请求回复的标识，用于关联相应记录或执行。
     * @param toolCallId        一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param toolName          可调用工具的注册名称，须与目录中声明的名称一致。
     * @param toolContent       当前审批请求使用的工具正文，供其处理与状态记录使用。
     * @param toolArgumentsJson 工具参数的 JSON 表示，供持久化或协议转换使用。
     * @param status            当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param requestedBy       当前审批请求使用的请求的按条件，供其处理与状态记录使用。
     * @param expiresAt         当前记录或授权的失效时间，用于过期检查。
     * @param decidedBy         当前审批请求使用的已决定按条件，供其处理与状态记录使用。
     * @param decidedAt         已决定的时间，用于记录对应生命周期节点。
     * @param createdAt         当前记录的创建时间。
     * @param updatedAt         当前记录最近一次更新的时间。
     * @param version           记录版本，用于乐观并发控制或区分协议版本。
     */
    public ApprovalRequest(
            String ownerKey,
            String sessionId,
            String turnId,
            String approvalId,
            String requestReplyId,
            String toolCallId,
            String toolName,
            String toolContent,
            String toolArgumentsJson,
            ApprovalStatus status,
            String requestedBy,
            Instant expiresAt,
            String decidedBy,
            Instant decidedAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this(
                ownerKey,
                sessionId,
                turnId,
                approvalId,
                requestReplyId,
                toolCallId,
                toolName,
                toolContent,
                toolArgumentsJson,
                null,
                status,
                requestedBy,
                expiresAt,
                decidedBy,
                decidedAt,
                createdAt,
                updatedAt,
                version);
    }

    /**
     * 创建审批请求，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey          宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId         会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId            单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param approvalId        待确认操作的记录标识，提交决定时用它定位原审批。
     * @param requestReplyId    请求回复的标识，用于关联相应记录或执行。
     * @param toolCallId        一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param toolName          可调用工具的注册名称，须与目录中声明的名称一致。
     * @param toolContent       当前审批请求使用的工具正文，供其处理与状态记录使用。
     * @param toolArgumentsJson 工具参数的 JSON 表示，供持久化或协议转换使用。
     * @param presentationJson  结构化呈现内容的持久化 JSON 表示。
     * @param status            当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param requestedBy       当前审批请求使用的请求的按条件，供其处理与状态记录使用。
     * @param expiresAt         当前记录或授权的失效时间，用于过期检查。
     * @param decidedBy         当前审批请求使用的已决定按条件，供其处理与状态记录使用。
     * @param decidedAt         已决定的时间，用于记录对应生命周期节点。
     * @param createdAt         当前记录的创建时间。
     * @param updatedAt         当前记录最近一次更新的时间。
     * @param version           记录版本，用于乐观并发控制或区分协议版本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
            "ownerKey",
            "sessionId",
            "turnId",
            "approvalId",
            "requestReplyId",
            "toolCallId",
            "toolName",
            "toolContent",
            "toolArgumentsJson",
            "presentationJson",
            "status",
            "requestedBy",
            "expiresAt",
            "decidedBy",
            "decidedAt",
            "createdAt",
            "updatedAt",
            "version"
    })
    public ApprovalRequest(
            String ownerKey,
            String sessionId,
            String turnId,
            String approvalId,
            String requestReplyId,
            String toolCallId,
            String toolName,
            String toolContent,
            String toolArgumentsJson,
            String presentationJson,
            ApprovalStatus status,
            String requestedBy,
            Instant expiresAt,
            String decidedBy,
            Instant decidedAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        ownerKey = Preconditions.requireText(ownerKey, "ownerKey 不能为空");
        sessionId = Preconditions.requireText(sessionId, "sessionId 不能为空");
        turnId = Preconditions.requireText(turnId, "turnId 不能为空");
        approvalId = Preconditions.requireText(approvalId, "approvalId 不能为空");
        toolCallId = Preconditions.requireText(toolCallId, "toolCallId 不能为空");
        toolName = Preconditions.requireText(toolName, "toolName 不能为空");
        toolContent = toolContent == null ? "" : toolContent;
        toolArgumentsJson = Objects.requireNonNull(toolArgumentsJson, "toolArgumentsJson");
        status = Objects.requireNonNull(status, "status");
        requestedBy = Preconditions.requireText(requestedBy, "requestedBy 不能为空");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能为负数");
        }

        this.ownerKey = ownerKey;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.approvalId = approvalId;
        this.requestReplyId = requestReplyId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolContent = toolContent;
        this.toolArgumentsJson = toolArgumentsJson;
        this.presentationJson = presentationJson;
        this.status = status;
        this.requestedBy = requestedBy;
        this.expiresAt = expiresAt;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }
}
