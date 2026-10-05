package dev.horizen.agent.observability.horizen;

/** 接收不可变 Horizen 追踪快照的输出端；输出失败不影响执行。 */
@FunctionalInterface
public interface HorizenTraceBatchSink {
    /** 快照被拒绝或丢弃时返回 false，实现不应抛出异常。 */
    boolean submit(HorizenTraceBatch batch);
}
