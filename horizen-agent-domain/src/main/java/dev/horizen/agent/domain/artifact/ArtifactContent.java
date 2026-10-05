package dev.horizen.agent.domain.artifact;

import dev.horizen.agent.common.validation.Identifiers;

import lombok.Getter;

/** 内容存储 Provider 成功写入 Artifact 后返回的不可变内容定位。 */
@Getter
public final class ArtifactContent {
    /** 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。 */
    private final String contentRef;

    /** 内容大小，单位为字节。 */
    private final long sizeBytes;

    /** 内容的 SHA-256 校验值，用于完整性校验。 */
    private final String checksumSha256;

    /**
     * 创建产物正文，初始化该组件所需的状态、配置或依赖。
     *
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @param sizeBytes 内容大小，单位为字节。
     * @param checksumSha256 内容的 SHA-256 校验值，用于完整性校验。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ArtifactContent(String contentRef, long sizeBytes, String checksumSha256) {
        if (contentRef == null || contentRef.isBlank()) {
            throw new IllegalArgumentException("contentRef must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        if (checksumSha256 == null || !Identifiers.SHA256.matcher(checksumSha256).matches()) {
            throw new IllegalArgumentException("checksumSha256 must be lowercase SHA-256");
        }
        this.contentRef = contentRef;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
    }
}
