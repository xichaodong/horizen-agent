package dev.horizen.agent.domain.workspace.release;

import java.util.Optional;

/** 完整工作区发布的读取端口；准备内容与获取版本必须保持发布身份一致。 */
public interface AgentReleaseRepository {
    /**
     * 查找当前。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<AgentReleaseManifest> findCurrent(AgentCatalogKey catalog);

    /**
     * 查找按条件标识。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param releaseId 发布记录标识，用于取得会话绑定的具体发布快照。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<AgentReleaseManifest> findById(AgentCatalogKey catalog, long releaseId);

    /** 执行任何模型或工具前，准备全部引用内容并获取租约。 */
    AgentReleaseSnapshot acquire(AgentReleaseManifest manifest);
}
