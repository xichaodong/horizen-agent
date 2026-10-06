package dev.horizen.agent.domain.workspace.snapshot;

import lombok.Data;

/**
 * 工作区属于某个所有者的 Session，Agent 运行时和缓存的生命周期独立管理。
 */
@Data
public final class WorkspaceSnapshotKey {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private final String ownerKey;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private final String sessionId;

    /**
     * 创建工作区快照键，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public WorkspaceSnapshotKey(String ownerKey, String sessionId) {
        if (ownerKey == null || ownerKey.isBlank() || ownerKey.length() > 191) {
            throw new IllegalArgumentException("Invalid ownerKey");
        }
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 191) {
            throw new IllegalArgumentException("Invalid sessionId");
        }
        this.ownerKey = ownerKey;
        this.sessionId = sessionId;
    }
}
