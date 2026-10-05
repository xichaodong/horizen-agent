package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/** 数据库行模型，与领域值对象分离。 */
@Data
public class WorkspaceRow {
    /** 工作区或发布所属 Project 的标识，参与资源归属校验。 */
    private Long projectId;

    /** 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。 */
    private String agentKey;

    /** 记录版本，用于乐观并发控制或区分协议版本。 */
    private Long version;

    /** 当前发布的标识，用于关联相应记录或执行。 */
    private Long currentReleaseId;

    /** 最近修改该记录的操作方标识，用于审计来源。 */
    private String updatedBy;

    /** 当前记录最近一次更新的时间。 */
    private Timestamp updatedAt;
}
