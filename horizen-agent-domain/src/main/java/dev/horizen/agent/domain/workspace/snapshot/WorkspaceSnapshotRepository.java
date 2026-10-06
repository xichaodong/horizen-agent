package dev.horizen.agent.domain.workspace.snapshot;

import java.io.InputStream;

/**
 * 工作区目录归档接口；下载流由调用者在使用后关闭。
 */
public interface WorkspaceSnapshotRepository extends AutoCloseable {
    /**
     * 上传工作区快照仓储。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @param archive    当前工作区快照仓储持有的归档对象，供相应处理步骤使用。
     */
    void upload(String snapshotId, InputStream archive) throws Exception;

    /**
     * 下载工作区快照仓储。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @return 本次操作返回的输入事件流结果。
     */
    InputStream download(String snapshotId) throws Exception;

    /**
     * 检查是否存在工作区快照仓储。
     *
     * @param snapshotId 工作区快照的持久引用，用于后续执行恢复文件内容。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean exists(String snapshotId) throws Exception;

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     */
    @Override
    default void close() {
    }
}
