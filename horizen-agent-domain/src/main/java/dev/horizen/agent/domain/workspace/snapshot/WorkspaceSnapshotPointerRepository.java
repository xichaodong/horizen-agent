package dev.horizen.agent.domain.workspace.snapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * 所有者 Session 最近提交的归档定位信息，与 Session 一起持久化。
 */
public interface WorkspaceSnapshotPointerRepository {
    /**
     * 查找快照标识。
     *
     * @param workspace 当前工作区快照指针仓储持有的工作区对象，供相应处理步骤使用。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<String> findSnapshotId(WorkspaceSnapshotKey workspace);

    /**
     * 检查compareAndSetCommitted对应的条件，供调用方选择后续处理分支。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param workspace  当前工作区快照指针仓储持有的工作区对象，供相应处理步骤使用。
     * @param expectedId 预期的标识，用于关联相应记录或执行。
     * @param nextId     下一个的标识，用于关联相应记录或执行。
     * @param turnId     单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    default boolean compareAndSetCommitted(
            WorkspaceSnapshotKey workspace, String expectedId, String nextId, String turnId) {
        synchronized (this) {
            if (!Objects.equals(findSnapshotId(workspace).orElse(null), expectedId)) return false;
            saveCommitted(workspace, nextId);
            return true;
        }
    }

    /**
     * 保存已提交。
     *
     * @param workspace  当前工作区快照指针仓储持有的工作区对象，供相应处理步骤使用。
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     */
    void saveCommitted(WorkspaceSnapshotKey workspace, String snapshotId);
}
