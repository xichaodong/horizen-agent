package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/** 数据库行模型，与领域值对象分离。 */
@Data
public class WorkspaceDocumentRow {
    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private String ownerKey;

    /** 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。 */
    private String agentKey;

    /** 工作区内的作用域键，用于把会话任务文件与 Agent 共享文档分开定位。 */
    private String scopeKey;

    /** 路径的内容摘要，供校验或去重使用。 */
    private String pathHash;

    /** 文件在受管工作区内的相对路径。 */
    private String filePath;

    /** 记录版本，用于乐观并发控制或区分协议版本。 */
    private Long version;

    /** 当前记录的创建时间。 */
    private Timestamp createdAt;

    /** 当前记录最近一次更新的时间。 */
    private Timestamp updatedAt;

    /** 文档在当前作用域内的相对路径。 */
    private String documentPath;

    /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
    private String content;

    /** 内容大小，单位为字节。 */
    private Long sizeBytes;
}
