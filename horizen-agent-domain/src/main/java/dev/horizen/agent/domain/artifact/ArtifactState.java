package dev.horizen.agent.domain.artifact;

/**
 * Artifact 从登记到回收的状态。
 */
public enum ArtifactState {
    /**
     * 产物已登记，实际内容尚在写入。
     */
    UPLOADING,
    /**
     * 产物内容已准备好，可按资源权限读取。
     */
    READY,
    /**
     * 当前操作或执行失败，具体原因由对应的失败信息说明。
     */
    FAILED,
    /**
     * 产物正在删除收敛中。
     */
    DELETING,
    /**
     * 产物已经进入删除状态。
     */
    DELETED;

    /**
     * 判断是否允许状态转换目标。
     *
     * @param target 本次转换、状态更新或内容写入的目标。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean canTransitionTo(ArtifactState target) {
        if (target == null || target == this || this == DELETED) {
            return false;
        }
        return switch (this) {
            case UPLOADING -> target == READY || target == FAILED || target == DELETING;
            case READY -> target == DELETING;
            case FAILED -> target == DELETING;
            case DELETING -> target == DELETED || target == FAILED;
            case DELETED -> false;
        };
    }
}
