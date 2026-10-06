package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 数据库行模型，与领域值对象分离。
 */
@Data
public class ArtifactRow {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey;

    /**
     * 产物资源标识；访问内容时仍需校验所属隔离范围。
     */
    private String artifactId;

    /**
     * 当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。
     */
    private String kind;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private String status;

    /**
     * 当前产物存储记录的可读标题，供宿主界面展示。
     */
    private String title;

    /**
     * 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。
     */
    private String mediaType;

    /**
     * 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     */
    private String contentRef;

    /**
     * 内容大小，单位为字节。
     */
    private Long sizeBytes;

    /**
     * 内容的 SHA-256 校验值，用于完整性校验。
     */
    private String checksumSha256;

    /**
     * 来源产物的标识，用于串联修改前后的版本关系。
     */
    private String parentArtifactId;

    /**
     * 当前事件、内容或执行的来源，供追踪生成关系与执行层级使用。
     */
    private String source;

    /**
     * 来源资源的引用，供追踪产物或事件的生成来源。
     */
    private String sourceRef;

    /**
     * 当前记录或授权的失效时间，用于过期检查。
     */
    private Timestamp expiresAt;

    /**
     * 当前记录的创建时间。
     */
    private Timestamp createdAt;

    /**
     * 当前记录最近一次更新的时间。
     */
    private Timestamp updatedAt;

    /**
     * 记录进入删除状态的时间，供生命周期和审计查询使用。
     */
    private Timestamp deletedAt;

    /**
     * 记录版本，用于乐观并发控制或区分协议版本。
     */
    private Long version;
}
