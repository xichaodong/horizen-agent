package dev.horizen.agent.domain.artifact;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 单次 Runtime 调用内收集已发布的 Artifact。
 */
public final class ArtifactEventCollector {
    /**
     * 尚未完成处理的工作或计数，供刷新、关闭与容量控制使用。
     */
    private final ConcurrentLinkedQueue<ArtifactDescriptor> pending = new ConcurrentLinkedQueue<>();

    /**
     * 记录产物事件收集器。
     *
     * @param artifact 当前产物事件收集器持有的产物对象，供相应处理步骤使用。
     */
    public void record(ArtifactDescriptor artifact) {
        if (artifact != null) {
            pending.add(artifact);
        }
    }

    /**
     * 取出待处理的产物事件收集器。
     *
     * @return 本次处理得到的结果集合。
     */
    public List<ArtifactDescriptor> drain() {
        List<ArtifactDescriptor> result = new ArrayList<>();
        ArtifactDescriptor item;
        while ((item = pending.poll()) != null) {
            result.add(item);
        }
        return List.copyOf(result);
    }
}
