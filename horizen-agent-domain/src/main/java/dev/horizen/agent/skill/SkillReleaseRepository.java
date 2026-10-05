package dev.horizen.agent.skill;

import java.util.List;
import java.util.Optional;

/** 不可变 Skill 发布版本的领域接口；可由 API、缓存或测试数据实现。 */
public interface SkillReleaseRepository {
    /**
     * 查找当前。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<SkillReleaseSnapshot> findCurrent(SkillCatalogKey catalog);

    /**
     * 查找按条件哈希。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param releaseHash 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<SkillReleaseSnapshot> findByHash(SkillCatalogKey catalog, String releaseHash);

    /** 无需访问发布来源即可获取的快照。 */
    List<SkillReleaseSnapshot> findAvailable(SkillCatalogKey catalog);
}
