package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.adapter.skill.horizen.AgentScopeSkillRepositoryAdapter;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.observability.horizen.HorizenHttpBatchExporter;
import dev.horizen.agent.observability.horizen.HorizenTraceConfig;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.ContextProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.MultimodalProperties;
import dev.horizen.agent.web.config.SandboxSnapshotProperties;

import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;

/** 具名运行时组装输入；各组装入口均需显式指定可选适配器。 */
@Value
@Builder
public final class RuntimeAssembly {
    /** 宿主绑定的配置对象，供组件组装与策略校验使用。 */
    AgentProperties properties;

    /** 上下文窗口、压缩与结果裁剪策略配置。 */
    ContextProperties contextProperties;

    /** 外部工具目录与调用的网关配置。 */
    GatewayProperties gatewayProperties;

    /** 远程沙箱启用、地址与隔离配置。 */
    E2bSandboxProperties sandboxProperties;

    /** 图片输入的数量、容量与引用策略配置。 */
    MultimodalProperties multimodalProperties;

    /** 本次运行使用的 Trace 采集与上报配置。 */
    HorizenTraceConfig traceConfig;

    /** 向观测服务异步提交批次数据的上报器。 */
    HorizenHttpBatchExporter horizenExporter;

    /** 为运行时渐进读取 Skill 正文与资源的仓储。 */
    AgentScopeSkillRepositoryAdapter skillRepository;

    /** 共享的 Agent 工作状态存储，用于跨实例执行与恢复。 */
    DistributedStore distributedStore;

    /** 负责会话占用、执行事实与正式消息的持久化端口。 */
    SessionTurnStore sessionTurns;

    /** 输入产物准备、内容访问与交付能力集合。 */
    ArtifactSupport artifactSupport;

    /** 澄清请求的存储或服务，用于回答处理与执行恢复。 */
    AskUserStore askUsers;

    /** 跨会话记忆与受管文本工作区的文档仓储。 */
    WorkspaceDocumentRepository workspaceDocuments;

    /** 保存与恢复完整工作区归档的快照仓储。 */
    SandboxSnapshotSpec snapshots;

    /** 沙箱工作区快照的容量与保存配置。 */
    SandboxSnapshotProperties snapshotProperties;

    /** 保存会话最近工作区快照引用的仓储。 */
    WorkspaceSnapshotPointerRepository snapshotPointers;

    /** 当前会话原发布对应的不可变本地工作区目录。 */
    Path publishedWorkspace;

    /** 共享 HTTP、工具、模型和沙箱客户端的组装结果。 */
    RuntimeInfrastructure infrastructure;
}
