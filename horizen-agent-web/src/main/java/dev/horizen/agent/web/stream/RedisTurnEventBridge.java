package dev.horizen.agent.web.stream;

import dev.horizen.agent.application.turn.TurnEventChannel;
import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.presentation.PresentationOutput;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AskUserEventDetails;
import dev.horizen.agent.storage.redis.RedisMessageBus;
import dev.horizen.agent.web.config.TurnEventProperties;

import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 Runtime Event 顺序写入 Redis，并为任意实例提供可回放事件流。
 */
public final class RedisTurnEventBridge implements TurnEventChannel, AutoCloseable {
    /**
     * 写入重试的固定取值，用于相应策略和边界判断。
     */
    private static final Retry WRITE_RETRY =
            Retry.backoff(2, Duration.ofMillis(25)).maxBackoff(Duration.ofMillis(100));

    /**
     * 当前执行控制与事件读写使用的消息总线。
     */
    private final MessageBus bus;

    /**
     * 宿主绑定的配置对象，供组件组装与策略校验使用。
     */
    private final TurnEventProperties properties;

    /**
     * 当前仍在读取执行增量日志的轮询器数量。
     */
    private final AtomicInteger activePollers = new AtomicInteger();

    /**
     * 组件是否已关闭，用于避免重复释放或继续接收新工作。
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 当前事件桥是否已进入关闭流程。
     */
    private final Sinks.Empty<Void> shutdown = Sinks.empty();

    /**
     * 读取的数量，供运行统计或容量控制使用。
     */
    private final AtomicLong readCount = new AtomicLong();

    /**
     * 空值读取的数量，供运行统计或容量控制使用。
     */
    private final AtomicLong emptyReadCount = new AtomicLong();

    /**
     * 读取失败的数量，供运行统计或容量控制使用。
     */
    private final AtomicLong readFailureCount = new AtomicLong();

    /**
     * 最大读取延迟纳秒的原子状态，供并发更新与统计读取使用。
     */
    private final AtomicLong maxReadLatencyNanos = new AtomicLong();

    /**
     * 创建Redis执行事件桥接器，初始化该组件所需的状态、配置或依赖。
     *
     * @param bus 当前Redis执行事件桥接器持有的消息总线对象，供相应处理步骤使用。
     */
    public RedisTurnEventBridge(MessageBus bus) {
        this(bus, new TurnEventProperties());
    }

    /**
     * 创建Redis执行事件桥接器，初始化该组件所需的状态、配置或依赖。
     *
     * @param bus        当前Redis执行事件桥接器持有的消息总线对象，供相应处理步骤使用。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     */
    public RedisTurnEventBridge(MessageBus bus, TurnEventProperties properties) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.properties = Objects.requireNonNull(properties, "properties");
        properties.validate();
    }

    /**
     * 发布Redis执行事件桥接器。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param event    当前Redis执行事件桥接器持有的事件对象，供相应处理步骤使用。
     * @return 本次操作返回的长整型结果。
     */
    public long publish(String ownerKey, AgentRuntimeEvent event) {
        return publish(ownerKey, event, 0L);
    }

    /**
     * 发布Redis执行事件桥接器。
     *
     * @param ownerKey         宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param event            当前Redis执行事件桥接器持有的事件对象，供相应处理步骤使用。
     * @param timelineSequence 当前Redis执行事件桥接器使用的时间线序号，供其处理与状态记录使用。
     * @return 本次操作返回的长整型结果。
     * @throws EventWriteException   当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public long publish(String ownerKey, AgentRuntimeEvent event, long timelineSequence) {
        Map<String, Object> payload = toPayload(event);
        payload.put("_timeline_sequence", timelineSequence);
        // 此 ID 在有限重试期间保持稳定，避免 Redis 回复丢失导致同一运行时事件写入两条 List 记录。
        payload.put("_write_id", UUID.randomUUID().toString());
        // 当前 Turn List 在有限 TTL 内只追加，不裁剪，游标才能稳定地等于 List 下标。
        String key = eventKey(ownerKey, event.getTurnId());
        String entryId;
        try {
            entryId =
                    Mono.defer(() -> append(key, payload))
                            .retryWhen(WRITE_RETRY)
                            .block(properties.getWriteTimeout());
        } catch (RuntimeException error) {
            throw new EventWriteException(error);
        }
        if (entryId == null || entryId.isBlank()) {
            throw new IllegalStateException("Redis Turn event 写入未返回序号");
        }
        long sequence = Long.parseLong(entryId);
        event.setStreamSequence(sequence);
        return sequence;
    }

    /**
     * 追加Redis执行事件桥接器。
     *
     * @param key     当前对象的查找或写入键。
     * @param payload 负载的索引映射，供按键查找或归并当前组件的数据。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private Mono<String> append(String key, Map<String, Object> payload) {
        if (bus instanceof RedisMessageBus redisBus) {
            return redisBus.logAppendBounded(
                    key,
                    payload,
                    0,
                    properties.getMaxEventBytes(),
                    properties.getMaxTurnBytes(),
                    properties.getMaxTurnEvents());
        }
        return bus.logAppend(key, payload, 0);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisTurnEventBridge处理步骤使用。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId   单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<AgentRuntimeEvent> replay(String ownerKey, String turnId) {
        return replay(ownerKey, turnId, 0L);
    }

    /**
     * 从 Redis List 的 1-based 游标之后恢复，不能传 MySQL timeline 序号。
     */
    public Flux<AgentRuntimeEvent> replay(String ownerKey, String turnId, long afterEventSequence) {
        String key = eventKey(ownerKey, turnId);
        return Flux.defer(
                () -> {
                    if (closed.get())
                        return Flux.error(new IllegalStateException("Turn event bridge is closed"));
                    activePollers.incrementAndGet();
                    AtomicReference<String> cursor =
                            new AtomicReference<>(
                                    afterEventSequence <= 0L
                                            ? null
                                            : Long.toString(afterEventSequence));
                    return poll(key, cursor, new AtomicInteger())
                            .takeUntilOther(shutdown.asMono())
                            .takeUntil(
                                    event ->
                                            terminal(event.getType())
                                                    || event.getType()
                                                    == AgentRuntimeEvent.Type
                                                    .APPROVAL_REQUIRED
                                                    || event.getType()
                                                    == AgentRuntimeEvent.Type
                                                    .ASK_USER_REQUIRED)
                            .doFinally(ignored -> activePollers.decrementAndGet());
                });
    }

    /**
     * 读取快照中的Redis执行事件桥接器。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId   单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次操作返回的事件快照结果。
     */
    public EventSnapshot snapshot(String ownerKey, String turnId) {
        String key = eventKey(ownerKey, turnId);
        List<AgentRuntimeEvent> events = new ArrayList<>();
        Map<Long, Long> timelineReferences = new LinkedHashMap<>();
        boolean present =
                bus instanceof RedisMessageBus redisBus
                        && Boolean.TRUE.equals(
                        redisBus.logExists(key).block(properties.getWriteTimeout()));
        String cursor = null;
        while (true) {
            List<BusEntry> batch =
                    bus.logRead(key, cursor, properties.getReadBatchSize())
                            .block(properties.getWriteTimeout());
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (BusEntry entry : batch) {
                AgentRuntimeEvent event = fromEntry(entry);
                events.add(event);
                Long timelineSequence = nullableLong(entry.payload().get("_timeline_sequence"));
                if (timelineSequence != null && timelineSequence > 0L) {
                    timelineReferences.put(event.getStreamSequence(), timelineSequence);
                }
            }
            cursor = batch.get(batch.size() - 1).entryId();
            if (batch.size() < properties.getReadBatchSize()) {
                break;
            }
        }
        long lastSequence = cursor == null ? 0L : Long.parseLong(cursor);
        return new EventSnapshot(
                List.copyOf(events), lastSequence, present, Map.copyOf(timelineReferences));
    }

    /**
     * 裁剪Redis执行事件桥接器。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId   单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    public void trim(String ownerKey, String turnId) {
        bus.logTrim(eventKey(ownerKey, turnId)).block(Duration.ofSeconds(3));
    }

    /**
     * 轮询Redis执行事件桥接器。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param key        当前对象的查找或写入键。
     * @param cursor     当前分页或回放位置，用于继续读取而不是资源身份校验。
     * @param emptyReads 空值Reads的原子状态，供并发更新与统计读取使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private Flux<AgentRuntimeEvent> poll(
            String key, AtomicReference<String> cursor, AtomicInteger emptyReads) {
        return Flux.defer(
                () -> {
                    long startedAt = System.nanoTime();
                    return bus.logRead(key, cursor.get(), properties.getReadBatchSize())
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnSuccess(
                                    entries -> {
                                        readCount.incrementAndGet();
                                        if (entries == null || entries.isEmpty())
                                            emptyReadCount.incrementAndGet();
                                        maxReadLatencyNanos.accumulateAndGet(
                                                System.nanoTime() - startedAt, Math::max);
                                    })
                            .doOnError(
                                    error -> {
                                        readFailureCount.incrementAndGet();
                                        maxReadLatencyNanos.accumulateAndGet(
                                                System.nanoTime() - startedAt, Math::max);
                                    })
                            .flatMapMany(
                                    entries -> {
                                        List<BusEntry> advanced = advance(cursor, entries);
                                        Duration delay =
                                                nextPollDelay(emptyReads, advanced.isEmpty());
                                        if (advanced.isEmpty()
                                                && emptyReads.get()
                                                >= properties.getMissingLogEmptyReads()
                                                && bus instanceof RedisMessageBus redisBus) {
                                            return redisBus.logExists(key)
                                                    .flatMapMany(
                                                            exists ->
                                                                    exists
                                                                            ? delayedPoll(
                                                                            key,
                                                                            cursor,
                                                                            emptyReads,
                                                                            delay)
                                                                            : Flux.error(
                                                                            new EventLogUnavailableException()));
                                        }
                                        return Flux.fromIterable(advanced)
                                                .map(entry -> fromEntry(entry))
                                                .concatWith(
                                                        delayedPoll(
                                                                key, cursor, emptyReads, delay));
                                    });
                });
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的事件桥接器状态结果。
     */
    public EventBridgeStatus status() {
        return new EventBridgeStatus(
                activePollers.get(),
                readCount.get(),
                emptyReadCount.get(),
                readFailureCount.get(),
                Duration.ofNanos(maxReadLatencyNanos.get()).toMillis());
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisTurnEventBridge处理步骤使用。
     *
     * @param key        当前对象的查找或写入键。
     * @param cursor     当前分页或回放位置，用于继续读取而不是资源身份校验。
     * @param emptyReads 空值Reads的原子状态，供并发更新与统计读取使用。
     * @param delay      延迟的时间配置，供等待、调度或失效判断使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private Flux<AgentRuntimeEvent> delayedPoll(
            String key, AtomicReference<String> cursor, AtomicInteger emptyReads, Duration delay) {
        return Mono.delay(delay).thenMany(Flux.defer(() -> poll(key, cursor, emptyReads)));
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param cursor  当前分页或回放位置，用于继续读取而不是资源身份校验。
     * @param entries 条目集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理得到的结果集合。
     */
    private static List<BusEntry> advance(AtomicReference<String> cursor, List<BusEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        cursor.set(entries.get(entries.size() - 1).entryId());
        return new ArrayList<>(entries);
    }

    /**
     * 转换为负载。
     *
     * @param event 当前Redis执行事件桥接器持有的事件对象，供相应处理步骤使用。
     * @return 按返回类型约定组织的结果映射。
     */
    private static Map<String, Object> toPayload(AgentRuntimeEvent event) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", event.getType().name());
        value.put("turnId", event.getTurnId());
        value.put("sessionId", event.getSessionId());
        value.put("id", event.getId());
        value.put("title", event.getTitle());
        value.put("text", event.getText());
        value.put("status", event.getStatus());
        value.put("toolName", event.getToolName());
        value.put(
                "details",
                event.getType() == AgentRuntimeEvent.Type.ASK_USER_REQUIRED
                        ? AskUserEventDetails.normalize(event.getDetails())
                        : event.getType() == AgentRuntimeEvent.Type.PRESENTATION_CREATED
                        ? presentationDetails(event.getDetails())
                        : event.getDetails());
        value.put("durationMs", event.getDurationMs());
        value.put("latencyMs", event.getLatencyMs());
        value.put("source", event.getSource());
        value.put("taskId", event.getTaskId());
        value.put("parentSessionId", event.getParentSessionId());
        value.put("agentId", event.getAgentId());
        value.put("depth", event.getDepth());
        return value;
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisTurnEventBridge处理步骤使用。
     *
     * @param details 当前事件或查询结果的补充细节，供状态解释与展示使用。
     * @return 本次操作返回的对象结果。
     */
    private static Object presentationDetails(Object details) {
        if (!(details instanceof PresentationOutput output)) return details;
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("blockId", output.getBlock().getBlockId());
        block.put("type", output.getBlock().getType());
        block.put("schemaVersion", output.getBlock().getSchemaVersion());
        block.put("position", output.getBlock().getPosition());
        block.put("data", output.getBlock().getData());
        return Map.of("toolCallId", output.getToolCallId(), "block", block);
    }

    /**
     * 从输入构造负载。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的Agent运行时事件结果。
     */
    private static AgentRuntimeEvent fromPayload(Map<String, Object> value) {
        return AgentRuntimeEvent.builder()
                .type(AgentRuntimeEvent.Type.valueOf(text(value, "type")))
                .turnId(text(value, "turnId"))
                .sessionId(text(value, "sessionId"))
                .id(text(value, "id"))
                .title(nullableText(value.get("title")))
                .text(nullableText(value.get("text")))
                .status(nullableText(value.get("status")))
                .toolName(nullableText(value.get("toolName")))
                .details(value.get("details"))
                .durationMs(nullableLong(value.get("durationMs")))
                .latencyMs(nullableLong(value.get("latencyMs")))
                .source(nullableText(value.get("source")))
                .taskId(nullableText(value.get("taskId")))
                .parentSessionId(nullableText(value.get("parentSessionId")))
                .agentId(nullableText(value.get("agentId")))
                .depth(nullableInteger(value.get("depth")))
                .build();
    }

    /**
     * 从输入构造条目。
     *
     * @param entry 当前Redis执行事件桥接器持有的条目对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent运行时事件结果。
     */
    private static AgentRuntimeEvent fromEntry(BusEntry entry) {
        AgentRuntimeEvent event = fromPayload(entry.payload());
        event.setStreamSequence(Long.parseLong(entry.entryId()));
        return event;
    }

    /**
     * 生成当前操作所需的eventKey文本，供调用方继续处理。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId   单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理生成或读取的文本。
     */
    private static String eventKey(String ownerKey, String turnId) {
        return "horizen:turn:events:" + encode(ownerKey) + ":" + encode(turnId);
    }

    /**
     * 编码Redis执行事件桥接器。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String encode(String value) {
        try {
            byte[] digest = DigestUtils.newSha256().digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param key   当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String text(Map<String, Object> value, String key) {
        String result = nullableText(value.get(key));
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Redis event 缺少 " + key);
        }
        return result;
    }

    /**
     * 生成当前操作所需的nullableText文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String nullableText(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisTurnEventBridge处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的长整型结果。
     */
    private static Long nullableLong(Object value) {
        return value instanceof Number number
                ? number.longValue()
                : value == null ? null : Long.valueOf(value.toString());
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisTurnEventBridge处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的整数结果。
     */
    private static Integer nullableInteger(Object value) {
        return value instanceof Number number
                ? number.intValue()
                : value == null ? null : Integer.valueOf(value.toString());
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisTurnEventBridge处理步骤使用。
     *
     * @param emptyReads 空值Reads的原子状态，供并发更新与统计读取使用。
     * @param empty      空值的状态标记，用于选择当前组件的处理路径。
     * @return 本次操作返回的耗时结果。
     */
    private Duration nextPollDelay(AtomicInteger emptyReads, boolean empty) {
        if (!empty) {
            emptyReads.set(0);
            return properties.getPollInterval();
        }
        int exponent = Math.min(emptyReads.incrementAndGet(), 3);
        long delay = properties.getPollInterval().toMillis() * (1L << exponent);
        return Duration.ofMillis(Math.min(delay, properties.getMaxPollInterval().toMillis()));
    }

    /**
     * 检查terminal对应的条件，供调用方选择后续处理分支。
     *
     * @param type 当前操作使用的目标类型或类别。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean terminal(AgentRuntimeEvent.Type type) {
        return type == AgentRuntimeEvent.Type.TURN_COMPLETED
                || type == AgentRuntimeEvent.Type.TURN_FAILED
                || type == AgentRuntimeEvent.Type.TURN_CANCELLED
                || type == AgentRuntimeEvent.Type.TURN_TIMED_OUT;
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     * 并发状态更新包含比较交换操作。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) shutdown.tryEmitEmpty();
    }

    /**
     * 从事件通道读取的执行增量与当前游标快照。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventSnapshot {
        /**
         * 当前执行或历史事件集合，供持久化、回放与观测使用。
         */
        private List<AgentRuntimeEvent> events;

        /**
         * 本次事件快照中已经读取到的最后增量游标。
         */
        private long lastSequence;

        /**
         * 存在的状态标记，用于选择当前组件的处理路径。
         */
        private boolean present;

        /**
         * 时间线引用集合的索引映射，供按键查找或归并当前组件的数据。
         */
        private Map<Long, Long> timelineReferences = Map.of();

        /**
         * 创建事件快照，初始化该组件所需的状态、配置或依赖。
         *
         * @param events       当前执行或历史事件集合，供持久化、回放与观测使用。
         * @param lastSequence 当前事件快照使用的最近序号，供其处理与状态记录使用。
         * @param present      存在的状态标记，用于选择当前组件的处理路径。
         */
        EventSnapshot(List<AgentRuntimeEvent> events, long lastSequence, boolean present) {
            this(events, lastSequence, present, Map.of());
        }
    }

    /**
     * 事件日志不可用异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。
     */
    public static final class EventLogUnavailableException extends IllegalStateException {
        /**
         * 创建事件日志不可用异常，初始化该组件所需的状态、配置或依赖。
         */
        EventLogUnavailableException() {
            super("Redis Turn event log 不存在或已过期");
        }
    }

    /**
     * 事件写入异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。
     */
    public static final class EventWriteException extends TurnEventChannel.EventWriteException {
        /**
         * 创建事件写入异常，初始化该组件所需的状态、配置或依赖。
         *
         * @param cause 导致当前失败的原始异常或原因，供错误传播与诊断使用。
         */
        EventWriteException(Throwable cause) {
            super("Redis Turn event 写入失败", cause);
        }
    }

    /**
     * 执行事件回放桥的轮询、读取与异常统计。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventBridgeStatus {
        /**
         * 当前仍在读取执行增量日志的轮询器数量。
         */
        private int activePollers;

        /**
         * 读取的数量，供运行统计或容量控制使用。
         */
        private long readCount;

        /**
         * 空值读取的数量，供运行统计或容量控制使用。
         */
        private long emptyReadCount;

        /**
         * 读取失败的数量，供运行统计或容量控制使用。
         */
        private long readFailureCount;

        /**
         * 最大读取延迟，单位为毫秒。
         */
        private long maxReadLatencyMs;
    }
}
