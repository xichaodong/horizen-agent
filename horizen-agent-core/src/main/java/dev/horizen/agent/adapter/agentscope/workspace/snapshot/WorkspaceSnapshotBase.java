package dev.horizen.agent.adapter.agentscope.workspace.snapshot;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 恢复前记录的最近一次已提交目录身份。
 */
@AllArgsConstructor
@Getter
public final class WorkspaceSnapshotBase {
    /**
     * 当前工作区快照基础的定位标识。
     */
    private String id;

    /**
     * 完成当前操作的committed步骤，按实现更新相应状态或依赖。
     *
     * @param id 目标对象的标识。
     */
    public void committed(String id) {
        this.id = id;
    }
}
