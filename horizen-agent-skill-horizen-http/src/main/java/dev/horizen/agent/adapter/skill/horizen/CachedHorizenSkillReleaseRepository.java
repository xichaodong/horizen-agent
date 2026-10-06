package dev.horizen.agent.adapter.skill.horizen;

import dev.horizen.agent.skill.SkillCatalogKey;
import dev.horizen.agent.skill.SkillReleaseManifest;
import dev.horizen.agent.skill.SkillReleaseRepository;
import dev.horizen.agent.skill.SkillReleaseSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Horizen API 适配器，附加按内容寻址且可重建的本地缓存。
 */
public final class CachedHorizenSkillReleaseRepository implements SkillReleaseRepository {
    /**
     * 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    private final HorizenSkillReleaseClient client;

    /**
     * 本组件使用的 {@code SkillReleaseCache} 状态或依赖，用于 cache 的处理。
     */
    private final SkillReleaseCache cache;

    /**
     * 创建缓存HorizenSkill发布仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param client 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @param cache  当前缓存HorizenSkill发布仓储持有的缓存对象，供相应处理步骤使用。
     */
    public CachedHorizenSkillReleaseRepository(
            HorizenSkillReleaseClient client, SkillReleaseCache cache) {
        this.client = Objects.requireNonNull(client, "client");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /**
     * 查找当前。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<SkillReleaseSnapshot> findCurrent(SkillCatalogKey catalog) {
        return client.fetchCurrent(catalog.getProjectId())
                .map(
                        manifest -> {
                            requireCatalog(catalog, manifest);
                            return cache.materialize(manifest, client);
                        });
    }

    /**
     * 查找按条件哈希。
     *
     * @param catalog     当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param releaseHash 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<SkillReleaseSnapshot> findByHash(SkillCatalogKey catalog, String releaseHash) {
        return cache.load(releaseHash)
                .filter(
                        snapshot -> {
                            requireCatalog(catalog, snapshot.getManifest());
                            return true;
                        });
    }

    /**
     * 查找可用。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<SkillReleaseSnapshot> findAvailable(SkillCatalogKey catalog) {
        return cache.loadAll().stream()
                .filter(snapshot -> snapshot.getManifest().getProjectId() == catalog.getProjectId())
                .toList();
    }

    /**
     * 取得并校验目录。
     *
     * @param catalog  当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param manifest 当前缓存HorizenSkill发布仓储持有的清单对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requireCatalog(SkillCatalogKey catalog, SkillReleaseManifest manifest) {
        if (manifest.getProjectId() != catalog.getProjectId()) {
            throw new IllegalStateException("Skill release projectId mismatch");
        }
    }
}
