package dev.horizen.agent.adapter.agentscope.workspace.filesystem;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 包含所有者、Agent 和 Session 的租约身份，不修改模型和工具的 RuntimeContext。
 */
public final class SessionWorkspaceIdentity {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private SessionWorkspaceIdentity() {
    }

    /**
     * 组合可信归属、Agent 与会话定位工作区，避免不同使用者共享同名会话目录。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param agentId 当前执行 Agent 的标识，用于区分主 Agent 与委派执行者。
     * @return 本次操作返回的沙箱Isolation键结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static SandboxIsolationKey key(RuntimeContext context, String agentId) {
        WorkspaceSnapshotKey workspace =
                new WorkspaceSnapshotKey(context.getUserId(), context.getSessionId());
        try {
            String slot =
                    HexFormat.of()
                            .formatHex(
                                    DigestUtils.newSha256()
                                            .digest(
                                                    (workspace.getOwnerKey()
                                                            + "\0"
                                                            + agentId
                                                            + "\0"
                                                            + workspace.getSessionId())
                                                            .getBytes(StandardCharsets.UTF_8)));
            return SandboxIsolationKey.resolve(
                            IsolationScope.SESSION,
                            RuntimeContext.builder().sessionId(slot).build(),
                            agentId)
                    .orElseThrow();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
