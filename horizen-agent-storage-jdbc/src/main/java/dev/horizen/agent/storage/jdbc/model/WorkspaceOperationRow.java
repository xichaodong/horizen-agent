package dev.horizen.agent.storage.jdbc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

/**
 * 数据库行模型，与领域值对象分离。
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class WorkspaceOperationRow {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey;

    /**
     * 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     */
    private String agentKey;

    /**
     * 工作区内的作用域键，用于把会话任务文件与 Agent 共享文档分开定位。
     */
    private String scopeKey;

    /**
     * 路径的内容摘要，供校验或去重使用。
     */
    private String pathHash;

    /**
     * 文件在受管工作区内的相对路径。
     */
    private String filePath;

    /**
     * 单次写入操作的标识，用于幂等提交和操作追踪。
     */
    private String operationId;

    /**
     * 工作区修改的操作类别，供审计和写入策略解释。
     */
    private String operationType;

    /**
     * 发起当前工作区修改的操作方类别。
     */
    private String actorType;

    /**
     * 审计记录在当前作用域中的排列序号。
     */
    private Long auditSequence;

    /**
     * 实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     */
    private String actorId;

    /**
     * 处理前的版本，供兼容或并发检查使用。
     */
    private Long beforeVersion;

    /**
     * 处理后的版本，供兼容或并发检查使用。
     */
    private Long afterVersion;

    /**
     * 操作之前的持久内容引用。
     */
    private String beforeRef;

    /**
     * 操作之后的持久内容引用。
     */
    private String afterRef;

    /**
     * 操作前内容的校验摘要，供审计与一致性比较使用。
     */
    private String beforeChecksum;

    /**
     * 操作后内容的校验摘要，供审计与一致性比较使用。
     */
    private String afterChecksum;

    /**
     * 操作前内容的字节数。
     */
    private Long beforeSize;

    /**
     * 操作后内容的字节数。
     */
    private Long afterSize;

    /**
     * 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。
     */
    private String mediaType;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private String sessionId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 一次工具调用的标识，用于配对参数、结果和审批事件。
     */
    private String toolCallId;

    /**
     * change的 JSON 表示，供持久化或协议转换使用。
     */
    private String changeJson;

    /**
     * 已应用的版本，供兼容或并发检查使用。
     */
    private Long appliedVersion;

    /**
     * 当前记录的创建时间。
     */
    private Timestamp createdAt;
}
