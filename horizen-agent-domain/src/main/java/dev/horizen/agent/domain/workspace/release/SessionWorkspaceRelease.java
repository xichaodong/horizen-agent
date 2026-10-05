package dev.horizen.agent.domain.workspace.release;

import dev.horizen.agent.common.validation.Identifiers;

import lombok.Value;

import java.util.Objects;

/** 属于 Session 的不可变发布身份，不属于沙箱或单个 Skill。 */
@Value
public class SessionWorkspaceRelease {
    /** 当前资源目录或目录定位键，用于查找可用发布与工具。 */
    AgentCatalogKey catalog;

    /** 发布记录标识，用于取得会话绑定的具体发布快照。 */
    long releaseId;

    /** 发布内容哈希，用于完整性校验和锁定会话的发布内容。 */
    String releaseHash;

    /**
     * 创建会话工作区发布，初始化该组件所需的状态、配置或依赖。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param releaseId 发布记录标识，用于取得会话绑定的具体发布快照。
     * @param releaseHash 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SessionWorkspaceRelease(AgentCatalogKey catalog, long releaseId, String releaseHash) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        if (releaseId <= 0
                || releaseHash == null
                || !Identifiers.SHA256.matcher(releaseHash).matches()) {
            throw new IllegalArgumentException("Invalid Session workspace release");
        }
        this.releaseId = releaseId;
        this.releaseHash = releaseHash;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param manifest 当前会话工作区发布持有的清单对象，供相应处理步骤使用。
     * @return 本次操作返回的会话工作区发布结果。
     */
    public static SessionWorkspaceRelease of(AgentReleaseManifest manifest) {
        return new SessionWorkspaceRelease(
                new AgentCatalogKey(manifest.getProjectId(), manifest.getAgentKey()),
                manifest.getReleaseId(),
                manifest.getReleaseHash());
    }
}
