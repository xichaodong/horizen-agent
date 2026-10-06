package dev.horizen.agent.adapter.agentscope.artifact;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactDescriptor;
import dev.horizen.agent.domain.artifact.ArtifactEventCollector;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.artifact.ArtifactLineageContext;
import dev.horizen.agent.domain.artifact.ArtifactPublicationRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;

import java.net.URLConnection;
import java.time.Instant;
import java.util.Objects;

/**
 * 将 AgentScope deliver_artifact 调用接入 Horizen 的 Artifact 生命周期。
 */
public final class ArtifactDeliveryBridge implements ArtifactDeliveryTarget {
    /**
     * 本组件调用的 {@code ArtifactLifecycleService} 依赖，负责 lifecycle 对应的处理步骤。
     */
    private final ArtifactLifecycleService lifecycle;

    /**
     * 创建产物交付桥接器，初始化该组件所需的状态、配置或依赖。
     *
     * @param lifecycle 提供生命周期能力的依赖，具体实现由当前组件的组装方传入。
     */
    public ArtifactDeliveryBridge(ArtifactLifecycleService lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * 计算或取得本方法声明的结果，供当前ArtifactDeliveryBridge处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的产物交付结果结果。
     */
    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext context, ArtifactDeliveryRequest request) {
        if (context == null || request == null) {
            return ArtifactDeliveryResult.fail("missing artifact runtime context");
        }
        ArtifactExecutionContext execution = context.get(ArtifactExecutionContext.class);
        if (execution == null) {
            return ArtifactDeliveryResult.fail("missing artifact turn context");
        }
        try {
            ArtifactLineageContext lineage = context.get(ArtifactLineageContext.class);
            String parentArtifactId = lineage == null ? null : lineage.singleParentArtifactId();
            String mediaType = URLConnection.guessContentTypeFromName(request.fileName());
            Artifact artifact =
                    lifecycle.publishFile(
                            new ArtifactPublicationRequest(
                                    context.getUserId(),
                                    context.getSessionId(),
                                    execution.getTurnId(),
                                    request.filePath() + "\n" + request.fileName(),
                                    request.fileName(),
                                    mediaType == null ? "application/octet-stream" : mediaType,
                                    request.content(),
                                    parentArtifactId,
                                    null,
                                    Instant.now()));
            ArtifactEventCollector collector = context.get(ArtifactEventCollector.class);
            if (collector != null) {
                collector.record(new ArtifactDescriptor(artifact));
            }
            return ArtifactDeliveryResult.success("artifactId=" + artifact.getArtifactId());
        } catch (RuntimeException error) {
            return ArtifactDeliveryResult.fail(
                    "artifact publish failed: " + error.getClass().getSimpleName());
        }
    }
}
