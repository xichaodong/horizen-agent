package dev.horizen.agent.domain.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 按工具调用收集展示块，避免并发工具的输出混合。 */
public final class PresentationEventCollector {
    /** 尚未完成处理的工作或计数，供刷新、关闭与容量控制使用。 */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<PresentationBlock>> pending =
            new ConcurrentHashMap<>();

    /**
     * 记录呈现事件收集器。
     *
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param block 当前呈现事件收集器持有的块对象，供相应处理步骤使用。
     */
    public void record(String toolCallId, PresentationBlock block) {
        if (toolCallId == null || toolCallId.isBlank() || block == null) return;
        pending.computeIfAbsent(toolCallId, ignored -> new ConcurrentLinkedQueue<>()).add(block);
    }

    /**
     * 取出待处理的呈现事件收集器。
     *
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @return 本次处理得到的结果集合。
     */
    public List<PresentationBlock> drain(String toolCallId) {
        ConcurrentLinkedQueue<PresentationBlock> queue = pending.remove(toolCallId);
        if (queue == null) return List.of();
        List<PresentationBlock> result = new ArrayList<>();
        PresentationBlock block;
        while ((block = queue.poll()) != null) result.add(block);
        return List.copyOf(result);
    }
}
