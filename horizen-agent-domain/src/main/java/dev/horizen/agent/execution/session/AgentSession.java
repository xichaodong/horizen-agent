package dev.horizen.agent.execution.session;

import dev.horizen.agent.common.validation.Preconditions;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;

/**
 * 长期存在的对话容器，通过 activeTurnId 指向当前执行。
 */
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class AgentSession {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    @EqualsAndHashCode.Include
    private final String ownerKey;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    @EqualsAndHashCode.Include
    private final String sessionId;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private SessionStatus status;

    /**
     * 会话当前占用的执行标识；无活跃执行时为空。
     */
    private String activeTurnId;

    /**
     * 工作区快照的持久引用，用于后续执行恢复文件内容。
     */
    private String snapshotId;

    /**
     * 该会话已经绑定的完整工作区发布，后续执行继续使用该版本。
     */
    private SessionWorkspaceRelease workspaceRelease;

    /**
     * 创建该记录的操作方标识，用于审计来源。
     */
    private String createdBy;

    /**
     * 当前Agent会话的可读标题，供宿主界面展示。
     */
    private String title;

    /**
     * 会话是否置顶，影响会话目录展示顺序。
     */
    private boolean pinned;

    /**
     * 会话最近一条正式消息的时间，供会话排序使用。
     */
    private Instant lastMessageAt;

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
     * 设置快照标识。
     *
     * @param value 待校验、转换或保存的原始值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void setSnapshotId(String value) {
        if (value != null && !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}")) {
            throw new IllegalArgumentException("Invalid snapshotId");
        }
        this.snapshotId = value;
    }

    /**
     * 设置工作区发布。
     *
     * @param value 待校验、转换或保存的原始值。
     */
    public void setWorkspaceRelease(SessionWorkspaceRelease value) {
        this.workspaceRelease = Objects.requireNonNull(value, "workspaceRelease");
    }

    /**
     * 创建Agent会话，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey      宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId     会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param status        当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param activeTurnId  会话当前占用的执行标识；无活跃执行时为空。
     * @param createdBy     当前Agent会话使用的创建按条件，供其处理与状态记录使用。
     * @param title         当前Agent会话的可读标题，供宿主界面展示。
     * @param pinned        会话是否置顶，影响会话目录展示顺序。
     * @param lastMessageAt 会话最近一条正式消息的时间，供会话排序使用。
     * @param createdAt     当前记录的创建时间。
     * @param updatedAt     当前记录最近一次更新的时间。
     * @param version       记录版本，用于乐观并发控制或区分协议版本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
            "ownerKey",
            "sessionId",
            "status",
            "activeTurnId",
            "createdBy",
            "title",
            "pinned",
            "lastMessageAt",
            "createdAt",
            "updatedAt",
            "version"
    })
    public AgentSession(
            String ownerKey,
            String sessionId,
            SessionStatus status,
            String activeTurnId,
            String createdBy,
            String title,
            boolean pinned,
            Instant lastMessageAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        ownerKey = Preconditions.requireText(ownerKey, "ownerKey 不能为空");
        sessionId = Preconditions.requireText(sessionId, "sessionId 不能为空");
        status = Objects.requireNonNull(status, "status");
        createdBy = Preconditions.requireText(createdBy, "createdBy 不能为空");
        title = Preconditions.requireText(title, "title 不能为空");
        lastMessageAt = Objects.requireNonNull(lastMessageAt, "lastMessageAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能为负数");
        }

        this.ownerKey = ownerKey;
        this.sessionId = sessionId;
        this.status = status;
        this.activeTurnId = activeTurnId;
        this.createdBy = createdBy;
        this.title = title;
        this.pinned = pinned;
        this.lastMessageAt = lastMessageAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }
}
