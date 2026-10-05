package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import java.time.Duration;
import java.util.Map;

/** 运行时事件计时边界：EventTiming。 */
final class EventTiming {
    /**
     * 计算或取得本方法声明的结果，供当前EventTiming处理步骤使用。
     *
     * @param starts 启动次数的索引映射，供按键查找或归并当前组件的数据。
     * @param key 当前对象的查找或写入键。
     * @return 本次操作返回的长整型结果。
     */
    static Long stepDuration(Map<String, Long> starts, String key) {
        Long started = starts.remove(key);
        return started == null ? null : elapsedMs(started);
    }

    /**
     * 计算或取得本方法声明的结果，供当前EventTiming处理步骤使用。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param startedAt 当前执行或执行段的开始时间。
     * @return 本次操作返回的长整型结果。
     */
    static long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
