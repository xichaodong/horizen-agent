package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 数据库行模型，与领域值对象分离。
 */
@Data
public class WorkspaceReleaseRow {
    /**
     * 当前工作区发布存储记录的定位标识。
     */
    private Long id;

    /**
     * 工作区或发布所属 Project 的标识，参与资源归属校验。
     */
    private Long projectId;

    /**
     * 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     */
    private String agentKey;

    /**
     * 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。
     */
    private Long releaseNo;

    /**
     * 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     */
    private String releaseHash;

    /**
     * 清单的 JSON 表示，供持久化或协议转换使用。
     */
    private String manifestJson;

    /**
     * 发布的可读说明，不参与运行时身份推断。
     */
    private String notes;

    /**
     * 创建该记录的操作方标识，用于审计来源。
     */
    private String createdBy;

    /**
     * 当前记录的创建时间。
     */
    private Timestamp createdAt;
}
