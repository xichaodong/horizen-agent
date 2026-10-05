package dev.horizen.agent.execution.turn;

import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.common.validation.Preconditions;
import dev.horizen.agent.identity.ExecutionIdentity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;

/** 原子创建 Turn、占用 Session 并保存用户消息所需的输入。 */
@Getter
@EqualsAndHashCode
@ToString
public class StartTurnCommand {
    /** 可信宿主解析的执行身份，供访问范围与审计使用。 */
    private final ExecutionIdentity identity;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private final String turnId;

    /** 调用方提供的请求标识，用于区分重复提交和关联幂等处理。 */
    private final String requestId;

    /** 持有当前执行段的执行实例标识，用于租约与跨实例控制。 */
    private final String executorId;

    /** 用户消息的标识，用于关联相应记录或执行。 */
    private final String userMessageId;

    /** 启动当前执行时需要作为正式用户消息保存的正文。 */
    private final String userMessage;

    /** 当前执行或执行段的开始时间。 */
    private final Instant startedAt;

    /** 当前操作允许继续执行的截止时间。 */
    private final Instant deadlineAt;

    /** 执行实例租约到期时间，用于判断执行权是否仍有效。 */
    private final Instant leaseExpiresAt;

    /**
     * 创建启动执行命令，初始化该组件所需的状态、配置或依赖。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param requestId 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param userMessageId 用户消息的标识，用于关联相应记录或执行。
     * @param userMessage 当前启动执行命令使用的用户消息，供其处理与状态记录使用。
     * @param startedAt 当前执行或执行段的开始时间。
     * @param deadlineAt 当前操作允许继续执行的截止时间。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
        "identity",
        "sessionId",
        "turnId",
        "requestId",
        "executorId",
        "userMessageId",
        "userMessage",
        "startedAt",
        "deadlineAt",
        "leaseExpiresAt"
    })
    public StartTurnCommand(
            ExecutionIdentity identity,
            String sessionId,
            String turnId,
            String requestId,
            String executorId,
            String userMessageId,
            String userMessage,
            Instant startedAt,
            Instant deadlineAt,
            Instant leaseExpiresAt) {
        identity = Objects.requireNonNull(identity, "identity");
        sessionId =
                Preconditions.requireText(
                        sessionId, "sessionId 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
        turnId =
                Preconditions.requireText(turnId, "turnId 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
        requestId =
                Preconditions.requireText(
                        requestId, "requestId 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
        executorId =
                Preconditions.requireText(
                        executorId, "executorId 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
        userMessageId =
                Preconditions.requireText(
                        userMessageId, "userMessageId 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
        userMessage = Objects.requireNonNull(userMessage, "userMessage");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (deadlineAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("deadlineAt 不能早于 startedAt");
        }
        if (!leaseExpiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("leaseExpiresAt 必须晚于 startedAt");
        }

        this.identity = identity;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.requestId = requestId;
        this.executorId = executorId;
        this.userMessageId = userMessageId;
        this.userMessage = userMessage;
        this.startedAt = startedAt;
        this.deadlineAt = deadlineAt;
        this.leaseExpiresAt = leaseExpiresAt;
    }
}
