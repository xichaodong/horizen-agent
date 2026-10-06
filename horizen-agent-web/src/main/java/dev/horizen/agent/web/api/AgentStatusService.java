package dev.horizen.agent.web.api;

import dev.horizen.agent.application.workspace.AgentReleaseService;
import dev.horizen.agent.observability.horizen.HorizenHttpBatchExporter;
import dev.horizen.agent.web.api.status.StatusApi;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.HorizenProperties;

import lombok.RequiredArgsConstructor;

import java.util.Locale;

/**
 * 构建运行状态视图，不参与执行流程。
 */
@RequiredArgsConstructor
public final class AgentStatusService {
    /**
     * 当前配置的 Agent 实例，承担模型与工具循环执行。
     */
    private final AgentProperties agent;

    /**
     * 外部工具目录与调用的网关适配器。
     */
    private final GatewayProperties gateway;

    /**
     * 观测采集与异步上报配置。
     */
    private final HorizenProperties tracing;

    /**
     * 沙箱启用与隔离参数配置。
     */
    private final E2bSandboxProperties sandbox;

    /**
     * 当前宿主组装的异步 Trace 上报器。
     */
    private final HorizenHttpBatchExporter exporter;

    /**
     * 是否启用发布集合处理。
     */
    private final boolean publicationsEnabled;

    /**
     * 完整工作区发布服务，提供会话绑定版本的准备与状态。
     */
    private final AgentReleaseService publications;

    /**
     * 把宿主配置和各可选集成状态组装为 HTTP 状态响应，不发起模型或工具执行。
     *
     * @return 本次操作返回的Agent状态结果。
     */
    StatusApi.AgentStatus status() {
        return new StatusApi.AgentStatus(
                agent.ready(),
                agent.getModelMode() == AgentProperties.ModelMode.SCRIPTED
                        ? "scripted-web"
                        : agent.getModelName(),
                agent.getBaseUrl(),
                agent.ready() ? "Agent API 已就绪" : "请配置 ARK_API_KEY 后重启应用",
                new StatusApi.GatewayStatus(
                        gateway.configured(), gateway.getMode().name().toLowerCase(Locale.ROOT)),
                tracing(),
                skillRelease(),
                sandbox(),
                publication());
    }

    /**
     * 返回完整工作区发布的启用开关、最近成功版本及准备失败摘要。
     *
     * @return 本次操作返回的工作区发布状态结果。
     */
    private StatusApi.WorkspaceReleaseStatus publication() {
        var current = publications == null ? null : publications.current().orElse(null);
        return new StatusApi.WorkspaceReleaseStatus(
                publicationsEnabled,
                current == null ? null : current.getReleaseNo(),
                current == null ? null : current.getReleaseHash(),
                publications == null ? null : publications.lastFailure().orElse(null));
    }

    /**
     * 读取本实例观测上报器的累计统计；未装配上报器时计数为零。
     *
     * @return 本次操作返回的观测状态结果。
     */
    private StatusApi.TracingStatus tracing() {
        return new StatusApi.TracingStatus(
                exporter != null,
                tracing.isCaptureContent(),
                exporter == null ? 0 : exporter.retriedCount(),
                exporter == null ? 0 : exporter.uploadedCount(),
                exporter == null ? 0 : exporter.failedCount(),
                exporter == null ? 0 : exporter.droppedCount(),
                exporter == null ? null : exporter.lastFailure());
    }

    /**
     * 从最近成功取得的完整发布投影 Skill 信息；没有当前发布时返回空标识和零数量。
     *
     * @return 本次操作返回的Skill发布状态结果。
     */
    private StatusApi.SkillReleaseStatus skillRelease() {
        var selected = publications == null ? null : publications.current().orElse(null);
        var current = selected == null ? null : selected.getSkillRelease();
        return new StatusApi.SkillReleaseStatus(
                publications != null,
                current == null ? null : current.getReleaseId(),
                current == null ? null : current.getReleaseNo(),
                current == null ? null : current.getReleaseHash(),
                current == null ? 0 : current.getSkillCount(),
                publications == null ? null : publications.lastFailure().orElse(null));
    }

    /**
     * 返回沙箱适配器配置，不创建、连接或启动实际沙箱。
     *
     * @return 本次操作返回的沙箱状态结果。
     */
    private StatusApi.SandboxStatus sandbox() {
        return new StatusApi.SandboxStatus(
                sandbox.isEnabled(),
                "e2b",
                "json-sync",
                sandbox.getIsolationScope().name().toLowerCase(Locale.ROOT));
    }
}
