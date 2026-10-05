package dev.horizen.agent.domain.artifact;

import java.util.LinkedHashSet;
import java.util.Set;

/** 将 Artifact 文件写入沙箱时收集的当前 Turn 输入血缘。 */
public final class ArtifactLineageContext {
    /** loaded产物的标识集合，用于批量关联相应记录。 */
    private final LinkedHashSet<String> loadedArtifactIds = new LinkedHashSet<>();

    /**
     * 记录Loaded。
     *
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     */
    public synchronized void recordLoaded(String artifactId) {
        if (artifactId != null && !artifactId.isBlank()) loadedArtifactIds.add(artifactId.trim());
    }

    /** 仅当本 Turn 恰好加载了一个不同的 Artifact 时，才推断其为父产物。 */
    public synchronized String singleParentArtifactId() {
        return loadedArtifactIds.size() == 1 ? loadedArtifactIds.iterator().next() : null;
    }

    /**
     * 读取loaded产物标识集合的当前值。
     *
     * @return {@link #loadedArtifactIds} 中保存的值。
     */
    public synchronized Set<String> loadedArtifactIds() {
        return Set.copyOf(loadedArtifactIds);
    }
}
