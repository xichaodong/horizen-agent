package dev.horizen.agent.domain.artifact;

import lombok.Getter;

/** 可返回给工具调用方和前端的 Artifact 摘要，不包含内容存储路径。 */
@Getter
public final class ArtifactDescriptor {
    /** 产物资源标识；访问内容时仍需校验所属隔离范围。 */
    private final String artifactId;

    /** 当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。 */
    private final ArtifactKind kind;

    /** 当前产物描述的可读标题，供宿主界面展示。 */
    private final String title;

    /** 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。 */
    private final String mediaType;

    /** 内容大小，单位为字节。 */
    private final Long sizeBytes;

    /** 来源产物的标识，用于串联修改前后的版本关系。 */
    private final String parentArtifactId;

    /**
     * 创建产物描述，初始化该组件所需的状态、配置或依赖。
     *
     * @param artifact 当前产物描述持有的产物对象，供相应处理步骤使用。
     */
    public ArtifactDescriptor(Artifact artifact) {
        this(
                artifact.getArtifactId(),
                artifact.getKind(),
                artifact.getTitle(),
                artifact.getMediaType(),
                artifact.getSizeBytes(),
                artifact.getParentArtifactId());
    }

    /**
     * 创建产物描述，初始化该组件所需的状态、配置或依赖。
     *
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param kind 当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。
     * @param title 当前产物描述的可读标题，供宿主界面展示。
     * @param mediaType 当前产物描述使用的媒体类型，供其处理与状态记录使用。
     * @param sizeBytes 内容大小，单位为字节。
     * @param parentArtifactId 来源产物的标识，用于串联修改前后的版本关系。
     */
    public ArtifactDescriptor(
            String artifactId,
            ArtifactKind kind,
            String title,
            String mediaType,
            Long sizeBytes,
            String parentArtifactId) {
        this.artifactId = artifactId;
        this.kind = kind;
        this.title = title;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.parentArtifactId = parentArtifactId;
    }
}
