package dev.horizen.agent.domain.artifact;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 协调 Artifact 元数据、内容存储和 Turn 输出引用。
 */
public final class ArtifactLifecycleService {
    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    private final ArtifactStore artifacts;

    /**
     * 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    private final ArtifactContentStore contents;

    /**
     * 创建产物生命周期服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param contents  资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    public ArtifactLifecycleService(ArtifactStore artifacts, ArtifactContentStore contents) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.contents = Objects.requireNonNull(contents, "contents");
    }

    /**
     * 发布文件。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的产物结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Artifact publishFile(ArtifactPublicationRequest request) {
        Objects.requireNonNull(request, "request");
        validateImageIfSupported(request.getMediaType(), request.content());
        String artifactId = artifactId(request);
        Artifact existing = artifacts.find(request.getOwnerKey(), artifactId).orElse(null);
        if (existing != null && existing.getState() == ArtifactState.READY) {
            ensureSameSource(existing, request);
            addOutputReference(request, artifactId);
            return existing;
        }
        if (existing != null && existing.getState() != ArtifactState.UPLOADING) {
            throw new IllegalStateException(
                    "artifact publication is not retryable in state " + existing.getState());
        }
        Artifact uploading =
                existing == null
                        ? new Artifact(
                        artifactId,
                        request.getOwnerKey(),
                        ArtifactKind.FILE,
                        ArtifactState.UPLOADING,
                        request.getTitle(),
                        request.getMediaType(),
                        null,
                        null,
                        null,
                        request.getParentArtifactId(),
                        ArtifactSource.AGENT,
                        request.getSourceRef(),
                        request.getExpiresAt(),
                        request.getNow(),
                        request.getNow(),
                        null,
                        0)
                        : existing;
        if (existing == null) {
            artifacts.create(uploading);
        } else {
            ensureSameSource(existing, request);
        }
        ArtifactContent uploaded = null;
        Artifact published;
        try {
            uploaded =
                    contents.put(
                            new ArtifactContentWrite(
                                    request.getOwnerKey(),
                                    artifactId,
                                    request.content(),
                                    request.getMediaType()));
            Artifact ready =
                    new Artifact(
                            artifactId,
                            request.getOwnerKey(),
                            ArtifactKind.FILE,
                            ArtifactState.READY,
                            request.getTitle(),
                            request.getMediaType(),
                            uploaded.getContentRef(),
                            uploaded.getSizeBytes(),
                            uploaded.getChecksumSha256(),
                            request.getParentArtifactId(),
                            ArtifactSource.AGENT,
                            request.getSourceRef(),
                            request.getExpiresAt(),
                            uploading.getCreatedAt(),
                            request.getNow(),
                            null,
                            uploading.getVersion());
            published = artifacts.update(ready, uploading.getVersion());
        } catch (RuntimeException error) {
            compensateUncommitted(uploading, uploaded, request.getNow(), error);
            throw error;
        }
        // READY 已提交，引用失败可重试，无需删除对应内容。
        addOutputReference(request, artifactId);
        return published;
    }

    /**
     * 上传用户提供的文件，创建尚未关联到任何 Turn 的 READY Artifact。
     */
    public Artifact uploadUserFile(
            String ownerKey, String title, String mediaType, byte[] content, Instant now) {
        if (ownerKey == null
                || ownerKey.isBlank()
                || title == null
                || title.isBlank()
                || mediaType == null
                || mediaType.isBlank()
                || content == null
                || now == null) {
            throw new IllegalArgumentException("artifact upload request is incomplete");
        }
        validateImageIfSupported(mediaType, content);
        String artifactId = "art_" + UUID.randomUUID().toString().replace("-", "");
        Artifact uploading =
                new Artifact(
                        artifactId,
                        ownerKey,
                        ArtifactKind.FILE,
                        ArtifactState.UPLOADING,
                        title,
                        mediaType,
                        null,
                        null,
                        null,
                        null,
                        ArtifactSource.USER,
                        "upload:" + artifactId,
                        null,
                        now,
                        now,
                        null,
                        0);
        artifacts.create(uploading);
        ArtifactContent uploaded = null;
        try {
            uploaded =
                    contents.put(
                            new ArtifactContentWrite(ownerKey, artifactId, content, mediaType));
            Artifact ready =
                    new Artifact(
                            artifactId,
                            ownerKey,
                            ArtifactKind.FILE,
                            ArtifactState.READY,
                            title,
                            mediaType,
                            uploaded.getContentRef(),
                            uploaded.getSizeBytes(),
                            uploaded.getChecksumSha256(),
                            null,
                            ArtifactSource.USER,
                            uploading.getSourceRef(),
                            null,
                            now,
                            now,
                            null,
                            0);
            return artifacts.update(ready, 0);
        } catch (RuntimeException error) {
            compensateUncommitted(uploading, uploaded, now, error);
            throw error;
        }
    }

    /**
     * 完成当前操作的compensateUncommitted步骤，按实现更新相应状态或依赖。
     *
     * @param uploading 当前产物生命周期服务持有的uploading对象，供相应处理步骤使用。
     * @param uploaded  当前产物生命周期服务持有的上报成功数对象，供相应处理步骤使用。
     * @param now       用于本次更新或过期判断的当前时间。
     * @param original  当前产物生命周期服务持有的原始对象，供相应处理步骤使用。
     */
    private void compensateUncommitted(
            Artifact uploading, ArtifactContent uploaded, Instant now, RuntimeException original) {
        // 成功以 CAS 将状态改为 FAILED，说明此版本从未提交 READY。如果写入已提交但确认丢失，CAS
        // 会失败，从而保留已引用的内容。
        if (!markFailed(uploading, now, original) || uploaded == null) return;
        try {
            contents.delete(uploaded.getContentRef());
        } catch (RuntimeException cleanup) {
            if (cleanup != original) original.addSuppressed(cleanup);
        }
    }

    /**
     * 增加输出引用。
     *
     * @param request    当前操作的请求参数。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     */
    private void addOutputReference(ArtifactPublicationRequest request, String artifactId) {
        String referenceId =
                "ref_"
                        + UUID.nameUUIDFromBytes(
                                (request.getOwnerKey()
                                        + '\0'
                                        + request.getSessionId()
                                        + '\0'
                                        + request.getTurnId()
                                        + '\0'
                                        + artifactId
                                        + '\0'
                                        + ArtifactReferenceRole.OUTPUT.name())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
        artifacts.addReference(
                new ArtifactReference(
                        referenceId,
                        request.getOwnerKey(),
                        artifactId,
                        request.getSessionId(),
                        request.getTurnId(),
                        ArtifactReferenceRole.OUTPUT,
                        null,
                        request.getNow()));
    }

    /**
     * 生成当前操作所需的artifactId文本，供调用方继续处理。
     *
     * @param request 当前操作的请求参数。
     * @return 本次处理生成或读取的文本。
     */
    private static String artifactId(ArtifactPublicationRequest request) {
        return "art_"
                + UUID.nameUUIDFromBytes(
                        (request.getOwnerKey()
                                + '\0'
                                + request.getTurnId()
                                + '\0'
                                + request.getSourceRef())
                                .getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    /**
     * 确保相同来源。
     *
     * @param artifact 当前产物生命周期服务持有的产物对象，供相应处理步骤使用。
     * @param request  当前操作的请求参数。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void ensureSameSource(Artifact artifact, ArtifactPublicationRequest request) {
        if (!request.getSourceRef().equals(artifact.getSourceRef())) {
            throw new IllegalStateException("artifact id belongs to another publication source");
        }
    }

    /**
     * 检查markFailed对应的条件，供调用方选择后续处理分支。
     *
     * @param uploading 当前产物生命周期服务持有的uploading对象，供相应处理步骤使用。
     * @param now       用于本次更新或过期判断的当前时间。
     * @param original  当前产物生命周期服务持有的原始对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private boolean markFailed(Artifact uploading, Instant now, RuntimeException original) {
        try {
            Artifact failed =
                    new Artifact(
                            uploading.getArtifactId(),
                            uploading.getOwnerKey(),
                            uploading.getKind(),
                            ArtifactState.FAILED,
                            uploading.getTitle(),
                            uploading.getMediaType(),
                            null,
                            null,
                            null,
                            uploading.getParentArtifactId(),
                            uploading.getSource(),
                            uploading.getSourceRef(),
                            uploading.getExpiresAt(),
                            uploading.getCreatedAt(),
                            now,
                            null,
                            uploading.getVersion());
            artifacts.update(failed, uploading.getVersion());
            return true;
        } catch (RuntimeException cleanup) {
            if (cleanup != original) original.addSuppressed(cleanup);
            return false;
        }
    }

    /**
     * 校验图片条件支持。
     *
     * @param mediaType 当前产物生命周期服务使用的媒体类型，供其处理与状态记录使用。
     * @param content   当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     */
    private static void validateImageIfSupported(String mediaType, byte[] content) {
        if (ArtifactImagePolicy.isSupported(mediaType)) {
            ArtifactImagePolicy.validateBytes(mediaType, content, Long.MAX_VALUE);
        }
    }
}
