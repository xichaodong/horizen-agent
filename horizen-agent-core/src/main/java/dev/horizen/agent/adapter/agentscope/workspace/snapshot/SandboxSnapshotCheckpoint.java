package dev.horizen.agent.adapter.agentscope.workspace.snapshot;

import dev.horizen.agent.adapter.agentscope.workspace.filesystem.SessionWorkspaceVolume;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;

/**
 * 在发出成功或暂停事件前，先提交归档及其共享指针。
 */
public final class SandboxSnapshotCheckpoint {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private SandboxSnapshotCheckpoint() {
    }

    /**
     * 保存沙箱快照检查点。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param states  提供states能力的依赖，具体实现由当前组件的组装方传入。
     * @param agentId 当前执行 Agent 的标识，用于区分主 Agent 与委派执行者。
     */
    public static void save(RuntimeContext context, AgentStateStore states, String agentId) {
        save(context, states, agentId, null);
    }

    /**
     * 保存沙箱快照检查点。
     *
     * @param context  当前执行上下文，提供关联标识和宿主绑定信息。
     * @param states   提供states能力的依赖，具体实现由当前组件的组装方传入。
     * @param agentId  当前执行 Agent 的标识，用于区分主 Agent 与委派执行者。
     * @param pointers 提供指针集合能力的依赖，具体实现由当前组件的组装方传入。
     * @throws IllegalStateException       当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws SnapshotCheckpointException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void save(
            RuntimeContext context,
            AgentStateStore states,
            String agentId,
            WorkspaceSnapshotPointerRepository pointers) {
        SessionWorkspaceVolume.save(context);
        SandboxAcquireResult acquired = context.get(SandboxAcquireResult.class);
        SandboxContext config = context.get(SandboxContext.class);
        if (acquired == null || config == null || !acquired.isSelfManaged()) return;
        var sandbox = acquired.getSandbox();
        var snapshot = sandbox.getState().getSnapshot();
        if (snapshot == null || !snapshot.isPersistenceEnabled()) return;
        try {
            var identity =
                    SandboxIsolationKey.resolve(config.getIsolationScope(), context, agentId)
                            .orElseThrow(
                                    () -> new IllegalStateException("Missing snapshot identity"));
            sandbox.stop();
            if (pointers != null) {
                var base = context.get(WorkspaceSnapshotBase.class);
                var execution = context.get(ArtifactExecutionContext.class);
                if (base == null
                        || !pointers.compareAndSetCommitted(
                        new WorkspaceSnapshotKey(
                                context.getUserId(), context.getSessionId()),
                        base.getId(),
                        snapshot.getId(),
                        execution == null ? null : execution.getTurnId())) {
                    throw new IllegalStateException(
                            "Session workspace changed or execution lease was lost");
                }
                base.committed(snapshot.getId());
                return; // Session 定位信息已持久化，避免使用缺少所有者信息的 Harness SESSION 缓存槽。
            }
            String serialized = config.getClient().serializeState(sandbox.getState());
            new SessionSandboxStateStore(states, agentId).save(identity, serialized);
        } catch (Exception error) {
            throw new SnapshotCheckpointException(error);
        }
    }

    /**
     * 快照检查点异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。
     */
    public static final class SnapshotCheckpointException extends RuntimeException {
        /**
         * 创建快照检查点异常，初始化该组件所需的状态、配置或依赖。
         *
         * @param cause 导致当前失败的原始异常或原因，供错误传播与诊断使用。
         */
        public SnapshotCheckpointException(Throwable cause) {
            super("Workspace snapshot checkpoint failed", cause);
        }
    }
}
