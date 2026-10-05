package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/** 数据库行模型，与领域值对象分离。 */
@Data
public class SessionRow {
    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private String ownerKey;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private String sessionId;

    /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
    private String status;

    /** 会话当前占用的执行标识；无活跃执行时为空。 */
    private String activeTurnId;

    /** 工作区快照的持久引用，用于后续执行恢复文件内容。 */
    private String snapshotId;

    /** 工作区或发布所属 Project 的标识，参与资源归属校验。 */
    private Long projectId;

    /** 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。 */
    private String agentKey;

    /** 工作区发布的标识，用于关联相应记录或执行。 */
    private Long workspaceReleaseId;

    /** 工作区发布的内容摘要，供校验或去重使用。 */
    private String workspaceReleaseHash;

    /** 创建该记录的操作方标识，用于审计来源。 */
    private String createdBy;

    /** 当前会话存储记录的可读标题，供宿主界面展示。 */
    private String title;

    /** 会话是否置顶，影响会话目录展示顺序。 */
    private Boolean pinned;

    /** 会话最近一条正式消息的时间，供会话排序使用。 */
    private Timestamp lastMessageAt;

    /** 会话下一条正式消息的序号，用于保持消息顺序。 */
    private Long nextMessageSequence;

    /** 当前记录的创建时间。 */
    private Timestamp createdAt;

    /** 当前记录最近一次更新的时间。 */
    private Timestamp updatedAt;

    /** 记录版本，用于乐观并发控制或区分协议版本。 */
    private Long version;
}
