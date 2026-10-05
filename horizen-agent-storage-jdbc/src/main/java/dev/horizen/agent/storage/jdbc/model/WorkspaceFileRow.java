package dev.horizen.agent.storage.jdbc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

/** 数据库行模型，与领域值对象分离。 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class WorkspaceFileRow {
    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private String ownerKey;

    /** 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。 */
    private String agentKey;

    /** 工作区内的作用域键，用于把会话任务文件与 Agent 共享文档分开定位。 */
    private String scopeKey;

    /** 文件所属工作区区域，决定访问与写入路由。 */
    private String workspaceArea;

    /** 工作区文件类别，用于选择内容和生命周期策略。 */
    private String fileKind;

    /** 该文件允许采用的写入策略。 */
    private String writePolicy;

    /** 路径的内容摘要，供校验或去重使用。 */
    private String pathHash;

    /** 文件在受管工作区内的相对路径。 */
    private String filePath;

    /** 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。 */
    private String contentRef;

    /** 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。 */
    private String mediaType;

    /** 内容的 SHA-256 校验值，用于完整性校验。 */
    private String checksumSha256;

    /** 内容大小，单位为字节。 */
    private Long sizeBytes;

    /** 记录版本，用于乐观并发控制或区分协议版本。 */
    private Long version;

    /** 当前记录的创建时间。 */
    private Timestamp createdAt;

    /** 当前记录最近一次更新的时间。 */
    private Timestamp updatedAt;
}
