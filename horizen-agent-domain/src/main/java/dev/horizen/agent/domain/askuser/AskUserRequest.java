package dev.horizen.agent.domain.askuser;

import lombok.Value;

import java.time.Instant;
import java.util.Objects;

/**
 * 持久化的问题单；questions/answers 使用稳定 JSON 协议。
 */
@Value
public class AskUserRequest {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private String sessionId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 待回答澄清请求的标识，恢复时与原问题记录关联。
     */
    private String askUserId;

    /**
     * 回复的标识，用于关联相应记录或执行。
     */
    private String replyId;

    /**
     * 一次工具调用的标识，用于配对参数、结果和审批事件。
     */
    private String toolCallId;

    /**
     * 问题集合的 JSON 表示，供持久化或协议转换使用。
     */
    private String questionsJson;

    /**
     * 回答集合的 JSON 表示，供持久化或协议转换使用。
     */
    private String answersJson;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private AskUserStatus status;

    /**
     * 当前记录的创建时间。
     */
    private Instant createdAt;

    /**
     * 当前记录或授权的失效时间，用于过期检查。
     */
    private Instant expiresAt;

    /**
     * 已解析的时间，用于记录对应生命周期节点。
     */
    private Instant resolvedAt;

    /**
     * 记录版本，用于乐观并发控制或区分协议版本。
     */
    private long version;

    /**
     * 创建提问用户请求，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey      宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId     会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId        单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param askUserId     待回答澄清请求的标识，恢复时与原问题记录关联。
     * @param replyId       回复的标识，用于关联相应记录或执行。
     * @param toolCallId    一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param questionsJson 问题集合的 JSON 表示，供持久化或协议转换使用。
     * @param answersJson   回答集合的 JSON 表示，供持久化或协议转换使用。
     * @param status        当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param createdAt     当前记录的创建时间。
     * @param expiresAt     当前记录或授权的失效时间，用于过期检查。
     * @param resolvedAt    已解析的时间，用于记录对应生命周期节点。
     * @param version       记录版本，用于乐观并发控制或区分协议版本。
     */
    public AskUserRequest(
            String ownerKey,
            String sessionId,
            String turnId,
            String askUserId,
            String replyId,
            String toolCallId,
            String questionsJson,
            String answersJson,
            AskUserStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant resolvedAt,
            long version) {
        this.ownerKey = text(ownerKey);
        this.sessionId = text(sessionId);
        this.turnId = text(turnId);
        this.askUserId = text(askUserId);
        this.replyId = replyId;
        this.toolCallId = text(toolCallId);
        this.questionsJson = text(questionsJson);
        this.answersJson = answersJson == null ? "[]" : answersJson;
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.resolvedAt = resolvedAt;
        this.version = version;
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param v 当前提问用户请求使用的V，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String text(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("value blank");
        return v.trim();
    }
}
