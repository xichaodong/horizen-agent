package dev.horizen.agent.execution.turn;

import dev.horizen.agent.common.validation.Preconditions;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次用户请求对应的一轮 Agent 执行。
 */
@Getter
@EqualsAndHashCode
@ToString
public class AgentTurn {
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
     * 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     */
    private String requestId;

    /**
     * 实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     */
    private String actorId;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private TurnStatus status;

    /**
     * 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     */
    private String executorId;

    /**
     * 当前执行或执行段的开始时间。
     */
    private Instant startedAt;

    /**
     * 执行结束时间；尚未结束的记录可以没有该时间。
     */
    private Instant finishedAt;

    /**
     * 当前操作允许继续执行的截止时间。
     */
    private Instant deadlineAt;

    /**
     * 执行实例租约到期时间，用于判断执行权是否仍有效。
     */
    private Instant leaseExpiresAt;

    /**
     * 机器可识别的失败分类，供状态恢复与错误展示使用。
     */
    private String failureCode;

    /**
     * 当前记录的创建时间。
     */
    private Instant createdAt;

    /**
     * 当前记录最近一次更新的时间。
     */
    private Instant updatedAt;

    /**
     * 记录版本，用于乐观并发控制或区分协议版本。
     */
    private long version;

    /**
     * 创建Agent执行，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey       宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId      会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId         单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param requestId      调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     * @param actorId        实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     * @param status         当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param executorId     持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param startedAt      当前执行或执行段的开始时间。
     * @param finishedAt     执行结束时间；尚未结束的记录可以没有该时间。
     * @param deadlineAt     当前操作允许继续执行的截止时间。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param failureCode    机器可识别的失败分类，供状态恢复与错误展示使用。
     * @param createdAt      当前记录的创建时间。
     * @param updatedAt      当前记录最近一次更新的时间。
     * @param version        记录版本，用于乐观并发控制或区分协议版本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
            "ownerKey",
            "sessionId",
            "turnId",
            "requestId",
            "actorId",
            "status",
            "executorId",
            "startedAt",
            "finishedAt",
            "deadlineAt",
            "leaseExpiresAt",
            "failureCode",
            "createdAt",
            "updatedAt",
            "version"
    })
    public AgentTurn(
            String ownerKey,
            String sessionId,
            String turnId,
            String requestId,
            String actorId,
            TurnStatus status,
            String executorId,
            Instant startedAt,
            Instant finishedAt,
            Instant deadlineAt,
            Instant leaseExpiresAt,
            String failureCode,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        ownerKey = Preconditions.requireText(ownerKey, "ownerKey 不能为空");
        sessionId = Preconditions.requireText(sessionId, "sessionId 不能为空");
        turnId = Preconditions.requireText(turnId, "turnId 不能为空");
        requestId = Preconditions.requireText(requestId, "requestId 不能为空");
        actorId = Preconditions.requireText(actorId, "actorId 不能为空");
        status = Objects.requireNonNull(status, "status");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (status.isTerminal() && finishedAt == null) {
            throw new IllegalArgumentException("终态 Turn 必须包含 finishedAt");
        }
        if (!status.isTerminal() && finishedAt != null) {
            throw new IllegalArgumentException("非终态 Turn 不能包含 finishedAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version 不能为负数");
        }

        this.ownerKey = ownerKey;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.requestId = requestId;
        this.actorId = actorId;
        this.status = status;
        this.executorId = executorId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.deadlineAt = deadlineAt;
        this.leaseExpiresAt = leaseExpiresAt;
        this.failureCode = failureCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }
}
