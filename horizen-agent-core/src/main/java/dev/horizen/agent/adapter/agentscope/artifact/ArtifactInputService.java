package dev.horizen.agent.adapter.agentscope.artifact;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactLineageContext;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 将已发布的 Artifact 下载到当前执行段沙箱，并记录 INPUT 引用。 */
public final class ArtifactInputService {
    /** 产物管理依赖或产物集合，用于引用、读取与交付资源。 */
    private final ArtifactStore artifacts;

    /** 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。 */
    private final ArtifactContentStore contents;

    /**
     * 创建产物输入服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param contents 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    public ArtifactInputService(ArtifactStore artifacts, ArtifactContentStore contents) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.contents = Objects.requireNonNull(contents, "contents");
    }

    /**
     * 把宿主输入资源写入当前执行可访问的文件系统，并返回对应资源位置。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param requestedPath 当前产物输入服务使用的请求的路径，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public String materialize(RuntimeContext context, String artifactId, String requestedPath) {
        Objects.requireNonNull(context, "context");
        ArtifactExecutionContext execution = context.get(ArtifactExecutionContext.class);
        if (execution == null) {
            throw new IllegalStateException("missing artifact turn context");
        }
        Artifact artifact =
                artifacts
                        .find(context.getUserId(), artifactId)
                        .orElseThrow(() -> new IllegalArgumentException("artifact not found"));
        if (artifact.getState() != ArtifactState.READY) {
            throw new IllegalStateException("artifact is not ready");
        }
        if (artifact.getKind() != ArtifactKind.FILE && artifact.getKind() != ArtifactKind.DATA) {
            throw new IllegalArgumentException("artifact cannot be materialized into sandbox");
        }
        String path = safePath(requestedPath, artifact.getArtifactId());
        AbstractFilesystem filesystem = context.get(AbstractFilesystem.class);
        if (filesystem == null) {
            throw new IllegalStateException("missing artifact filesystem context");
        }
        byte[] bytes = contents.get(artifact.getContentRef());
        List<FileUploadResponse> uploaded =
                filesystem.uploadFiles(context, List.of(Map.entry(path, bytes)));
        if (uploaded.isEmpty() || !uploaded.get(0).isSuccess()) {
            String error = uploaded.isEmpty() ? "no upload result" : uploaded.get(0).error();
            throw new IllegalStateException("artifact upload to sandbox failed: " + error);
        }
        ArtifactLineageContext lineage = context.get(ArtifactLineageContext.class);
        if (lineage != null) lineage.recordLoaded(artifact.getArtifactId());
        String referenceId =
                "ref_"
                        + UUID.nameUUIDFromBytes(
                                        (context.getUserId()
                                                        + '\0'
                                                        + context.getSessionId()
                                                        + '\0'
                                                        + execution.getTurnId()
                                                        + '\0'
                                                        + artifact.getArtifactId()
                                                        + '\0'
                                                        + ArtifactReferenceRole.INPUT.name())
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        artifacts.addReference(
                new ArtifactReference(
                        referenceId,
                        context.getUserId(),
                        artifact.getArtifactId(),
                        context.getSessionId(),
                        execution.getTurnId(),
                        ArtifactReferenceRole.INPUT,
                        null,
                        Instant.now()));
        return uploaded.get(0).path();
    }

    /**
     * 规范化输入资源路径，拒绝把资源写到允许工作目录之外。
     *
     * @param requestedPath 当前产物输入服务使用的请求的路径，供其处理与状态记录使用。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String safePath(String requestedPath, String artifactId) {
        String value =
                requestedPath == null || requestedPath.isBlank()
                        ? "inputs/" + artifactId
                        : requestedPath.trim();
        if (value.startsWith("/")
                || value.contains("\\")
                || value.contains("\0")
                || value.equals(".")
                || value.equals("..")
                || value.contains("../")) {
            throw new IllegalArgumentException("path must be a safe workspace-relative path");
        }
        AbstractFilesystem.validatePath(value);
        return value;
    }
}
