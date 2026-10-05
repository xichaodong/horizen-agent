package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.adapter.agentscope.artifact.ArtifactInputService;
import dev.horizen.agent.adapter.agentscope.artifact.ArtifactTurnInputService;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.artifact.ArtifactStore;

import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 由 Web 组装入口构建的 Artifact 领域组件。 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Getter
public final class ArtifactSupport {
    /** 把本次生成内容登记为可交付产物的目标实现。 */
    private final ArtifactDeliveryTarget deliveryTarget;

    /** 解析与准备输入产物内容的服务。 */
    private final ArtifactInputService inputs;

    /** 按会话历史选择并关联当前执行输入资源的服务。 */
    private final ArtifactTurnInputService turnInputs;

    /** 本组件调用的 {@code ArtifactLifecycleService} 依赖，负责 lifecycle 对应的处理步骤。 */
    private final ArtifactLifecycleService lifecycle;

    /** 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。 */
    private final ArtifactContentStore contents;

    /** 产物管理依赖或产物集合，用于引用、读取与交付资源。 */
    private final ArtifactStore artifacts;
}
