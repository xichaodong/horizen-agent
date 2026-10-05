package dev.horizen.agent.execution.turn;

import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.common.validation.Preconditions;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;

/** 对指定 Turn 执行一次受状态机约束的状态迁移。 */
@Getter
@EqualsAndHashCode
@ToString
public class TransitionTurnCommand {
    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private final String ownerKey;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private final String turnId;

    /** 本次请求希望原执行转换到的状态。 */
    private final TurnStatus targetStatus;

    /** 持有当前执行段的执行实例标识，用于租约与跨实例控制。 */
    private final String executorId;

    /** 执行实例租约到期时间，用于判断执行权是否仍有效。 */
    private final Instant leaseExpiresAt;

    /** occurred的时间，用于记录对应生命周期节点。 */
    private final Instant occurredAt;

    /** 机器可识别的失败分类，供状态恢复与错误展示使用。 */
    private final String failureCode;

    /** 助手消息的标识，用于关联相应记录或执行。 */
    private final String assistantMessageId;

    /** 本次终态提交需要保存的正式助手回复。 */
    private final String assistantMessage;

    /** 调用方观察到的版本，更新时用于识别并发修改。 */
    private final Long expectedVersion;

    /** 即使 Turn 已进入下一轮等待，也需拒绝过期的恢复请求。 */
    public TransitionTurnCommand expectVersion(long version) {
        if (version < 0) throw new IllegalArgumentException("expectedVersion 不能为负数");
        return new TransitionTurnCommand(
                ownerKey,
                sessionId,
                turnId,
                targetStatus,
                executorId,
                leaseExpiresAt,
                occurredAt,
                failureCode,
                assistantMessageId,
                assistantMessage,
                version);
    }

    /**
     * 创建状态转换执行命令，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param targetStatus 当前状态转换执行命令持有的目标状态对象，供相应处理步骤使用。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param occurredAt occurred的时间，用于记录对应生命周期节点。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @param assistantMessageId 助手消息的标识，用于关联相应记录或执行。
     * @param assistantMessage 当前状态转换执行命令使用的助手消息，供其处理与状态记录使用。
     */
    public TransitionTurnCommand(
            String ownerKey,
            String sessionId,
            String turnId,
            TurnStatus targetStatus,
            String executorId,
            Instant leaseExpiresAt,
            Instant occurredAt,
            String failureCode,
            String assistantMessageId,
            String assistantMessage) {
        this(
                ownerKey,
                sessionId,
                turnId,
                targetStatus,
                executorId,
                leaseExpiresAt,
                occurredAt,
                failureCode,
                assistantMessageId,
                assistantMessage,
                null);
    }

    /**
     * 创建状态转换执行命令，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param targetStatus 当前状态转换执行命令持有的目标状态对象，供相应处理步骤使用。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param occurredAt occurred的时间，用于记录对应生命周期节点。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @param assistantMessageId 助手消息的标识，用于关联相应记录或执行。
     * @param assistantMessage 当前状态转换执行命令使用的助手消息，供其处理与状态记录使用。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
        "ownerKey",
        "sessionId",
        "turnId",
        "targetStatus",
        "executorId",
        "leaseExpiresAt",
        "occurredAt",
        "failureCode",
        "assistantMessageId",
        "assistantMessage",
        "expectedVersion"
    })
    public TransitionTurnCommand(
            String ownerKey,
            String sessionId,
            String turnId,
            TurnStatus targetStatus,
            String executorId,
            Instant leaseExpiresAt,
            Instant occurredAt,
            String failureCode,
            String assistantMessageId,
            String assistantMessage,
            Long expectedVersion) {
        ownerKey =
                Preconditions.requireText(
                        ownerKey, "ownerKey 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
        sessionId =
                Preconditions.requireText(
                        sessionId, "sessionId 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
        turnId =
                Preconditions.requireText(turnId, "turnId 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (targetStatus == TurnStatus.RUNNING) {
            executorId =
                    Preconditions.requireText(
                            executorId, "executorId 不能为空", Identifiers.STORED_KEY_MAX_LENGTH);
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        }
        if (targetStatus == TurnStatus.COMPLETED) {
            assistantMessageId =
                    Preconditions.requireText(
                            assistantMessageId,
                            "assistantMessageId 不能为空",
                            Identifiers.STORED_KEY_MAX_LENGTH);
            assistantMessage = Objects.requireNonNull(assistantMessage, "assistantMessage");
        }

        this.ownerKey = ownerKey;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.targetStatus = targetStatus;
        this.executorId = executorId;
        this.leaseExpiresAt = leaseExpiresAt;
        this.occurredAt = occurredAt;
        this.failureCode = failureCode;
        this.assistantMessageId = assistantMessageId;
        this.assistantMessage = assistantMessage;
        if (expectedVersion != null && expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion 不能为负数");
        }
        this.expectedVersion = expectedVersion;
    }
}
