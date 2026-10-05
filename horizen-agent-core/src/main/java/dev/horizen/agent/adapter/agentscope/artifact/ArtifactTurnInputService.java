package dev.horizen.agent.adapter.agentscope.artifact;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactImagePolicy;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;
import dev.horizen.agent.runtime.api.AgentInputAttachment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** 解析当前所有者的 Artifact 引用，为支持的图像创建仅供模型使用的 BOS URL。 */
public final class ArtifactTurnInputService {
    /** 产物管理依赖或产物集合，用于引用、读取与交付资源。 */
    private final ArtifactStore artifacts;

    /** 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。 */
    private final ArtifactContentStore contents;

    /**
     * 创建产物执行输入服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param contents 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    public ArtifactTurnInputService(ArtifactStore artifacts, ArtifactContentStore contents) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.contents = Objects.requireNonNull(contents, "contents");
    }

    /**
     * 按原会话历史与本次输入解析可用产物，不直接复用上一轮沙箱目录。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param artifactIds 本次操作引用的产物标识集合，内容读取由产物服务处理。
     * @param directImages direct图片集合的状态标记，用于选择当前组件的处理路径。
     * @param maxImages 当前产物执行输入服务使用的最大图片集合，供其处理与状态记录使用。
     * @param maxImageBytes 最大图片的字节数，用于容量或传输限制。
     * @param maxTotalImageBytes 最大总计图片的字节数，用于容量或传输限制。
     * @param imageUrlExpiresSeconds 图片URL过期，单位为秒。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public List<AgentInputAttachment> resolve(
            String ownerKey,
            String sessionId,
            String turnId,
            List<String> artifactIds,
            boolean directImages,
            int maxImages,
            long maxImageBytes,
            long maxTotalImageBytes,
            int imageUrlExpiresSeconds) {
        if (artifactIds == null || artifactIds.isEmpty()) return List.of();
        List<AgentInputAttachment> images = new ArrayList<>();
        List<Artifact> resolved = new ArrayList<>();
        long total = 0;
        for (String artifactId : artifactIds) {
            Artifact artifact =
                    artifacts
                            .find(ownerKey, artifactId)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "artifact not found or not owned: "
                                                            + artifactId));
            if (artifact.getState() != ArtifactState.READY || artifact.getContentRef() == null) {
                throw new IllegalArgumentException("artifact is not ready: " + artifactId);
            }
            resolved.add(artifact);
            String mediaType =
                    artifact.getMediaType() == null
                            ? ""
                            : artifact.getMediaType().toLowerCase(Locale.ROOT);
            if (!mediaType.startsWith("image/")) continue;
            mediaType = ArtifactImagePolicy.requireSupportedMediaType(mediaType);
            if (!directImages) continue;
            if (images.size() >= maxImages) {
                throw new IllegalArgumentException("too many image artifacts; max=" + maxImages);
            }
            if (artifact.getSizeBytes() == null) {
                throw new IllegalArgumentException(
                        "image artifact is missing size metadata: " + artifactId);
            }
            if (artifact.getSizeBytes() > maxImageBytes) {
                throw new IllegalArgumentException(
                        "image artifact exceeds per-image limit: " + artifactId);
            }
            total += artifact.getSizeBytes();
            if (total > maxTotalImageBytes) {
                throw new IllegalArgumentException("image artifacts exceed total byte limit");
            }
            String url =
                    contents.createDownloadUrl(artifact.getContentRef(), imageUrlExpiresSeconds)
                            .toString();
            images.add(
                    new AgentInputAttachment(
                            artifact.getArtifactId(), artifact.getTitle(), mediaType, url));
        }
        for (Artifact artifact : resolved) {
            addInputReference(ownerKey, sessionId, turnId, artifact.getArtifactId());
        }
        return List.copyOf(images);
    }

    /**
     * 将选中的产物作为本次用户消息输入引用保存。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     */
    private void addInputReference(
            String ownerKey, String sessionId, String turnId, String artifactId) {
        String referenceId =
                "ref_"
                        + UUID.nameUUIDFromBytes(
                                        (ownerKey
                                                        + '\0'
                                                        + sessionId
                                                        + '\0'
                                                        + turnId
                                                        + '\0'
                                                        + artifactId
                                                        + '\0'
                                                        + ArtifactReferenceRole.INPUT.name())
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        artifacts.addReference(
                new ArtifactReference(
                        referenceId,
                        ownerKey,
                        artifactId,
                        sessionId,
                        turnId,
                        ArtifactReferenceRole.INPUT,
                        null,
                        Instant.now()));
    }
}
