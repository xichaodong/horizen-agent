package dev.horizen.agent.execution.session;

import dev.horizen.agent.common.validation.Preconditions;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 面向用户的正式对话消息。
 */
@Getter
@EqualsAndHashCode
@ToString
public class ConversationMessage {
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
     * 会话消息的标识，用于历史查询与过程事件关联。
     */
    private final String messageId;

    /**
     * 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
     */
    private final MessageRole role;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private final MessageStatus status;

    /**
     * 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     */
    private final String content;

    /**
     * 本次操作引用的产物标识集合，内容读取由产物服务处理。
     */
    private List<String> artifactIds = List.of();

    /**
     * 设置产物标识集合。
     *
     * @param ids 目标对象的标识集合。
     */
    public void setArtifactIds(List<String> ids) {
        artifactIds = List.copyOf(ids == null ? List.of() : ids);
    }

    /**
     * 当前记录在对应序列中的位置，用于排序或继续读取。
     */
    private final long sequence;

    /**
     * 当前记录的创建时间。
     */
    private final Instant createdAt;

    /**
     * 当前记录最近一次更新的时间。
     */
    private final Instant updatedAt;

    /**
     * 创建对话消息，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param messageId 会话消息的标识，用于历史查询与过程事件关联。
     * @param role      消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
     * @param status    当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param content   当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param sequence  当前记录在对应序列中的位置，用于排序或继续读取。
     * @param createdAt 当前记录的创建时间。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
            "ownerKey",
            "sessionId",
            "turnId",
            "messageId",
            "role",
            "status",
            "content",
            "sequence",
            "createdAt",
            "updatedAt"
    })
    public ConversationMessage(
            String ownerKey,
            String sessionId,
            String turnId,
            String messageId,
            MessageRole role,
            MessageStatus status,
            String content,
            long sequence,
            Instant createdAt,
            Instant updatedAt) {
        ownerKey = Preconditions.requireText(ownerKey, "ownerKey 不能为空");
        sessionId = Preconditions.requireText(sessionId, "sessionId 不能为空");
        turnId = Preconditions.requireText(turnId, "turnId 不能为空");
        messageId = Preconditions.requireText(messageId, "messageId 不能为空");
        role = Objects.requireNonNull(role, "role");
        status = Objects.requireNonNull(status, "status");
        content = Objects.requireNonNull(content, "content");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence 必须为正数");
        }

        this.ownerKey = ownerKey;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.messageId = messageId;
        this.role = role;
        this.status = status;
        this.content = content;
        this.sequence = sequence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
