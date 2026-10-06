package dev.horizen.agent.adapter.agentscope.workspace.snapshot;

import dev.horizen.agent.adapter.agentscope.workspace.filesystem.SessionWorkspaceIdentity;
import dev.horizen.agent.adapter.agentscope.workspace.filesystem.SessionWorkspaceVolume;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxState;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 解析永久定位信息，与恢复及检查点操作共享执行租约。
 */
public final class DurableWorkspaceSandboxContext {
    /**
     * 当前执行 Agent 的标识，用于区分主 Agent 与委派执行者。
     */
    private final String agentId;

    /**
     * 创建当前模型或沙箱对象时使用的默认策略配置。
     */
    private final SandboxContext defaults;

    /**
     * 按会话定位最近持久工作区快照的指针仓储。
     */
    private final WorkspaceSnapshotPointerRepository pointers;

    /**
     * 当前执行的并发或权限保护对象，在受保护操作前取得准入。
     */
    private final SandboxExecutionGuard guard;

    /**
     * 本次启动是否按新的执行状态准备工作区。
     */
    private final Function<String, SandboxState> freshState;

    /**
     * 创建持久工作区沙箱上下文，初始化该组件所需的状态、配置或依赖。
     *
     * @param agentId    当前执行 Agent 的标识，用于区分主 Agent 与委派执行者。
     * @param defaults   当前持久工作区沙箱上下文持有的默认值对象，供相应处理步骤使用。
     * @param pointers   提供指针集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param guard      当前持久工作区沙箱上下文持有的防护对象，供相应处理步骤使用。
     * @param freshState 当前持久工作区沙箱上下文持有的fresh工作状态对象，供相应处理步骤使用。
     */
    public DurableWorkspaceSandboxContext(
            String agentId,
            SandboxContext defaults,
            WorkspaceSnapshotPointerRepository pointers,
            SandboxExecutionGuard guard,
            Function<String, SandboxState> freshState) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.pointers = Objects.requireNonNull(pointers, "pointers");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.freshState = Objects.requireNonNull(freshState, "freshState");
    }

    /**
     * 准备持久工作区沙箱上下文。
     *
     * @param call 当前持久工作区沙箱上下文持有的调用对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void prepare(RuntimeContext call) {
        try {
            SandboxIsolationKey key = SessionWorkspaceIdentity.key(call, agentId);
            ScopeLease lease = new ScopeLease(guard.tryEnter(key));
            call.put(ScopeLease.class, lease);
            String id =
                    pointers.findSnapshotId(
                                    new WorkspaceSnapshotKey(call.getUserId(), call.getSessionId()))
                            .orElse(null);
            call.put(WorkspaceSnapshotBase.class, new WorkspaceSnapshotBase(id));
            // Harness 的 SESSION 缓存槽仅包含 sessionId。应从按所有者隔离的持久化 Session
            // 定位信息恢复，避免不同所有者共享凭据或缓存。
            SandboxState state = freshState.apply(id);
            state.setWorkspaceSpec(defaults.getWorkspaceSpec().copy());
            call.put(
                    SandboxContext.class,
                    SandboxContext.builder()
                            .client(defaults.getClient())
                            .clientOptions(defaults.getClientOptions())
                            .workspaceSpec(defaults.getWorkspaceSpec())
                            .snapshotSpec(defaults.getSnapshotSpec())
                            .isolationScope(defaults.getIsolationScope())
                            .externalSandboxState(state)
                            .build());
        } catch (Exception error) {
            release(call);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to resolve durable workspace", error);
        }
    }

    /**
     * 释放持久工作区沙箱上下文。
     *
     * @param call 当前持久工作区沙箱上下文持有的调用对象，供相应处理步骤使用。
     */
    public static void release(RuntimeContext call) {
        SessionWorkspaceVolume.release(call);
        ScopeLease lease = call.get(ScopeLease.class);
        if (lease != null) lease.close();
    }

    /**
     * 持久工作区沙箱上下文内部的作用域租约，封装该步骤需要的状态或输入输出。
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class ScopeLease implements AutoCloseable {
        /**
         * 当前操作取得的工作区或发布资源租约，使用结束后归还。
         */
        private final SandboxLease lease;

        /**
         * 组件是否已关闭，用于避免重复释放或继续接收新工作。
         */
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
         * 并发状态更新包含比较交换操作。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) lease.close();
        }
    }
}
