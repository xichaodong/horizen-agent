package dev.horizen.agent.observability;

/** 接收 Trace 事件，具体实现负责决定存储或导出方式。 */
@FunctionalInterface
public interface TraceSink {
    /**
     * 记录Trace上报端。
     *
     * @param event 当前Trace上报端持有的事件对象，供相应处理步骤使用。
     */
    void record(TraceEvent event);
}
