package dev.horizen.agent.domain.artifact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Artifact 元数据及其 Turn 引用关系的持久化边界。
 */
public interface ArtifactStore {
    /**
     * 创建产物存储。
     *
     * @param artifact 当前产物存储持有的产物对象，供相应处理步骤使用。
     * @return 本次操作返回的产物结果。
     */
    Artifact create(Artifact artifact);

    /**
     * 查找产物存储。
     *
     * @param ownerKey   宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<Artifact> find(String ownerKey, String artifactId);

    /**
     * 更新产物存储。
     *
     * @param artifact        当前产物存储持有的产物对象，供相应处理步骤使用。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @return 本次操作返回的产物结果。
     */
    Artifact update(Artifact artifact, long expectedVersion);

    /**
     * 增加引用。
     *
     * @param reference 当前产物存储持有的引用对象，供相应处理步骤使用。
     */
    void addReference(ArtifactReference reference);

    /**
     * 查询列表中的引用集合。
     *
     * @param ownerKey   宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<ArtifactReference> listReferences(String ownerKey, String artifactId);

    /**
     * 同一所有者的会话中，关联到各 Turn 的 Artifact 引用。
     */
    default List<ArtifactReference> listReferencesForSession(String ownerKey, String sessionId) {
        return List.of();
    }

    /**
     * 仅返回近期 READY 输出；可用高效的数量受限查询替换默认视图构建。
     */
    default List<Artifact> listRecentOutputs(String ownerKey, String sessionId, int limit) {
        if (limit <= 0) return List.of();
        List<ArtifactReference> refs = listReferencesForSession(ownerKey, sessionId);
        LinkedHashMap<String, Artifact> found = new LinkedHashMap<>();
        for (int index = refs.size() - 1; index >= 0 && found.size() < limit; index--) {
            ArtifactReference ref = refs.get(index);
            if (ref.getRole() == ArtifactReferenceRole.OUTPUT
                    && !found.containsKey(ref.getArtifactId())) {
                find(ownerKey, ref.getArtifactId())
                        .filter(a -> a.getState() == ArtifactState.READY)
                        .ifPresent(a -> found.put(a.getArtifactId(), a));
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * 由同一所有者的会话引用的就绪 Artifact 元数据。
     */
    default List<Artifact> listForSession(String ownerKey, String sessionId, int limit) {
        return List.of();
    }

    /**
     * 查询列表中的目标范围会话。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    default List<Artifact> listForSession(String ownerKey, String sessionId) {
        return listForSession(ownerKey, sessionId, Integer.MAX_VALUE);
    }

    /**
     * 按受限查询的相同顺序，分页读取 READY 元数据。
     */
    default List<Artifact> listForSession(
            String ownerKey, String sessionId, int limit, int offset) {
        if (limit <= 0 || offset < 0) return List.of();
        return listForSession(ownerKey, sessionId, Math.addExact(offset, limit)).stream()
                .skip(offset)
                .limit(limit)
                .toList();
    }
}
