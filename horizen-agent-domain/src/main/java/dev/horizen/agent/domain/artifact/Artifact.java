package dev.horizen.agent.domain.artifact;

import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.common.validation.Preconditions;

import lombok.Value;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 云端可持久引用的 Agent 资源；内容本身由存储 Provider 管理。
 */
@Value
public class Artifact {

    /**
     * 产物资源标识；访问内容时仍需校验所属隔离范围。
     */
    private String artifactId;

    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey;

    /**
     * 当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。
     */
    private ArtifactKind kind;

    /**
     * 当前工作状态或状态存储对象，供执行与恢复流程使用。
     */
    private ArtifactState state;

    /**
     * 当前产物的可读标题，供宿主界面展示。
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
    private ArtifactSource source;

    /**
     * 来源资源的引用，供追踪产物或事件的生成来源。
     */
    private String sourceRef;

    /**
     * 当前记录或授权的失效时间，用于过期检查。
     */
    private Instant expiresAt;

    /**
     * 当前记录的创建时间。
     */
    private Instant createdAt;

    /**
     * 当前记录最近一次更新的时间。
     */
    private Instant updatedAt;

    /**
     * 记录进入删除状态的时间，供生命周期和审计查询使用。
     */
    private Instant deletedAt;

    /**
     * 记录版本，用于乐观并发控制或区分协议版本。
     */
    private long version;

    /**
     * 创建产物，初始化该组件所需的状态、配置或依赖。
     *
     * @param artifactId       产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param ownerKey         宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param kind             当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。
     * @param state            当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @param title            当前产物的可读标题，供宿主界面展示。
     * @param mediaType        当前产物使用的媒体类型，供其处理与状态记录使用。
     * @param contentRef       内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @param sizeBytes        内容大小，单位为字节。
     * @param checksumSha256   内容的 SHA-256 校验值，用于完整性校验。
     * @param parentArtifactId 来源产物的标识，用于串联修改前后的版本关系。
     * @param source           待解析或转换的来源对象。
     * @param sourceRef        来源资源的引用，供追踪产物或事件的生成来源。
     * @param expiresAt        当前记录或授权的失效时间，用于过期检查。
     * @param createdAt        当前记录的创建时间。
     * @param updatedAt        当前记录最近一次更新的时间。
     * @param deletedAt        记录进入删除状态的时间，供生命周期和审计查询使用。
     * @param version          记录版本，用于乐观并发控制或区分协议版本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
            "artifactId",
            "ownerKey",
            "kind",
            "state",
            "title",
            "mediaType",
            "contentRef",
            "sizeBytes",
            "checksumSha256",
            "parentArtifactId",
            "source",
            "sourceRef",
            "expiresAt",
            "createdAt",
            "updatedAt",
            "deletedAt",
            "version"
    })
    public Artifact(
            String artifactId,
            String ownerKey,
            ArtifactKind kind,
            ArtifactState state,
            String title,
            String mediaType,
            String contentRef,
            Long sizeBytes,
            String checksumSha256,
            String parentArtifactId,
            ArtifactSource source,
            String sourceRef,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt,
            long version) {
        this.artifactId = requireId(artifactId, "artifactId", Identifiers.ARTIFACT_ID);
        this.ownerKey = requireId(ownerKey, "ownerKey", Identifiers.ARTIFACT_OWNER_KEY);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.state = Objects.requireNonNull(state, "state");
        this.title = Preconditions.requireText(title, "title must not be blank").trim();
        this.mediaType = optionalText(mediaType);
        this.contentRef = optionalText(contentRef);
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = optionalChecksum(checksumSha256);
        this.parentArtifactId = optionalId(parentArtifactId, "parentArtifactId");
        this.source = Objects.requireNonNull(source, "source");
        this.sourceRef = optionalText(sourceRef);
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.deletedAt = deletedAt;
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        if (state == ArtifactState.READY && this.contentRef == null) {
            throw new IllegalArgumentException("READY Artifact must have contentRef");
        }
        if (state == ArtifactState.DELETED && deletedAt == null) {
            throw new IllegalArgumentException("DELETED Artifact must have deletedAt");
        }
        if (state != ArtifactState.DELETED && deletedAt != null) {
            throw new IllegalArgumentException("only DELETED Artifact may have deletedAt");
        }
    }

    /**
     * 取得并校验标识。
     *
     * @param value   待校验、转换或保存的原始值。
     * @param field   当前产物使用的字段，供其处理与状态记录使用。
     * @param pattern 当前产物持有的校验模式对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireId(String value, String field, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be an opaque safe identifier");
        }
        return value;
    }

    /**
     * 生成当前操作所需的optionalId文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前产物使用的字段，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String optionalId(String value, String field) {
        return value == null || value.isBlank()
                ? null
                : requireId(value, field, Identifiers.ARTIFACT_ID);
    }

    /**
     * 生成当前操作所需的optionalText文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 生成当前操作所需的optionalChecksum文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String optionalChecksum(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!Identifiers.SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("checksumSha256 must be lowercase SHA-256");
        }
        return value;
    }
}
