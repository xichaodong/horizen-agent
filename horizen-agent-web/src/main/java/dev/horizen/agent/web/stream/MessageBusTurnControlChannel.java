package dev.horizen.agent.web.stream;

import dev.horizen.agent.application.turn.TurnControlChannel;

import io.agentscope.harness.agent.bus.MessageBus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 跨实例取消的 Redis 队列适配器。 */
public final class MessageBusTurnControlChannel implements TurnControlChannel {
    /** 当前组件的诊断日志器。 */
    private static final Logger log = LoggerFactory.getLogger(MessageBusTurnControlChannel.class);

    /** 当前执行控制与事件读写使用的消息总线。 */
    private final MessageBus bus;

    /** 超时的时间配置，供等待、调度或失效判断使用。 */
    private final Duration timeout;

    /**
     * 创建消息消息总线执行控制通道，初始化该组件所需的状态、配置或依赖。
     *
     * @param bus 当前消息消息总线执行控制通道持有的消息总线对象，供相应处理步骤使用。
     * @param timeout 本次等待允许持续的最长时间。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public MessageBusTurnControlChannel(MessageBus bus, Duration timeout) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");
    }

    /**
     * 取出待处理的消息消息总线执行控制通道。
     *
     * @param instanceId 当前服务实例标识，用于区分分布式执行与资源统计。
     * @param limit 本次处理或返回数量上限。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<List<CancelSignal>> drain(String instanceId, int limit) {
        return Mono.defer(() -> bus.queueDrain(key(instanceId), limit))
                .map(
                        entries -> {
                            ArrayList<CancelSignal> signals = new ArrayList<>();
                            for (var entry : entries) {
                                Map<String, Object> value = entry.payload();
                                if (value == null) {
                                    log.warn(
                                            "Discarding cancel control without payload [entryId={}]",
                                            entry.entryId());
                                    continue;
                                }
                                if (!"cancel".equals(value.get("type"))) continue;
                                try {
                                    signals.add(
                                            new CancelSignal(
                                                    text(value, "ownerKey"),
                                                    text(value, "sessionId"),
                                                    text(value, "turnId")));
                                } catch (IllegalArgumentException error) {
                                    log.warn(
                                            "Discarding malformed cancel control [entryId={}]",
                                            entry.entryId());
                                }
                            }
                            return List.copyOf(signals);
                        });
    }

    /**
     * 发送消息消息总线执行控制通道。
     *
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param signal 当前消息消息总线执行控制通道持有的信号对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void send(String executorId, CancelSignal signal) {
        String entry =
                bus.queuePush(
                                key(executorId),
                                Map.of(
                                        "type",
                                        "cancel",
                                        "ownerKey",
                                        signal.getOwnerKey(),
                                        "sessionId",
                                        signal.getSessionId(),
                                        "turnId",
                                        signal.getTurnId()))
                        .block(timeout);
        if (entry == null)
            throw new IllegalStateException("Cancellation queue did not acknowledge request");
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param payload 负载的索引映射，供按键查找或归并当前组件的数据。
     * @param name 需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String text(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Invalid cancel control " + name);
        }
        return text;
    }

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param instanceId 当前服务实例标识，用于区分分布式执行与资源统计。
     * @return 本次处理生成或读取的文本。
     */
    private static String key(String instanceId) {
        return "horizen:control:" + instanceId;
    }
}
