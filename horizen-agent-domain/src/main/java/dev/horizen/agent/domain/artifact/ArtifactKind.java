package dev.horizen.agent.domain.artifact;

/**
 * Artifact 内容的表现形式。
 */
public enum ArtifactKind {
    /**
     * 以实际文件内容保存的产物。
     */
    FILE,
    /**
     * 指向外部内容的链接产物。
     */
    LINK,
    /**
     * 以结构化数据保存的产物。
     */
    DATA
}
