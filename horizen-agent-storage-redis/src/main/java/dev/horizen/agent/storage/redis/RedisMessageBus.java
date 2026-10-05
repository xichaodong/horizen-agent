package dev.horizen.agent.storage.redis;

import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.UnifiedJedis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 使用 Redis List 和 Pub/Sub 实现 AgentScope MessageBus，兼容 Redis 4.0。 */
public final class RedisMessageBus implements MessageBus {

    /** 日志追加SCRIPT使用的固定标识或协议文本。 */
    private static final String LOG_APPEND_SCRIPT =
            """
local body = cjson.decode(ARGV[1])
local write_id = body['_write_id']
local list_len = redis.call('LLEN', KEYS[1])
local sequence_value = redis.call('GET', KEYS[2])
if list_len > 0 and not sequence_value then
    return redis.error_reply('LOG_SEQUENCE_MISSING')
end
if write_id and redis.call('HEXISTS', KEYS[4], write_id) == 1 then
    return redis.call('HGET', KEYS[4], write_id)
end
if write_id and list_len > 0 then
    local last = cjson.decode(redis.call('LINDEX', KEYS[1], -1))
    if last['writeId'] == write_id then
        return last['entryId']
    end
end
local max_total_events = tonumber(ARGV[6])
local current_sequence = tonumber(sequence_value or '0')
if max_total_events and max_total_events > 0 and current_sequence >= max_total_events then
    return redis.error_reply('LOG_TOTAL_EVENTS_LIMIT')
end
local sequence = redis.call('INCR', KEYS[2])
body['_write_id'] = nil
local value = cjson.encode({entryId = tostring(sequence), payload = body, writeId = write_id})
local max_total_bytes = tonumber(ARGV[5])
local value_bytes = string.len(value)
if max_total_bytes and max_total_bytes > 0 then
    local current_bytes_value = redis.call('GET', KEYS[5])
    if list_len > 0 and not current_bytes_value then
        redis.call('DECR', KEYS[2])
        return redis.error_reply('LOG_BYTES_COUNTER_MISSING')
    end
    local current_bytes = tonumber(current_bytes_value or '0')
    if current_bytes + value_bytes > max_total_bytes then
        redis.call('DECR', KEYS[2])
        return redis.error_reply('LOG_TOTAL_BYTES_LIMIT')
    end
    redis.call('INCRBY', KEYS[5], value_bytes)
end
redis.call('RPUSH', KEYS[1], value)
local max_len = tonumber(ARGV[2])
if max_len and max_len > 0 then
    redis.call('LTRIM', KEYS[1], -max_len, -1)
end
local first = redis.call('LINDEX', KEYS[1], 0)
if first then
    redis.call('SET', KEYS[3], cjson.decode(first).entryId)
end
if write_id then
    redis.call('HSET', KEYS[4], write_id, tostring(sequence))
end
redis.call('EXPIRE', KEYS[1], ARGV[3])
redis.call('EXPIRE', KEYS[2], ARGV[3])
redis.call('EXPIRE', KEYS[3], ARGV[3])
redis.call('EXPIRE', KEYS[4], ARGV[3])
if max_total_bytes and max_total_bytes > 0 then
    redis.call('EXPIRE', KEYS[5], ARGV[3])
end
return tostring(sequence)
""";

    /** 队列排空SCRIPT使用的固定标识或协议文本。 */
    private static final String QUEUE_DRAIN_SCRIPT =
            """
      local result = {}
      local count = tonumber(ARGV[1])
      for index = 1, count do
          local value = redis.call('LPOP', KEYS[1])
          if not value then
              break
          end
          table.insert(result, value)
      end
      return result
      """;

    /** 当前组件用于普通状态读写的 Redis 命令客户端。 */
    private final UnifiedJedis commands;

    /** 当前组件用于实时订阅的专用连接或连接池。 */
    private final JedisPool subscriptions;

    /** 存储键前缀，用于区分本应用的数据与其他使用方。 */
    private final String keyPrefix;

    /** 保留时间，单位为秒。 */
    private final long ttlSeconds;

    /** 订阅接收缓冲允许保留的事件数量上限。 */
    private final int subscriptionBufferCapacity;

    /**
     * 创建Redis消息消息总线，初始化该组件所需的状态、配置或依赖。
     *
     * @param commands 当前Redis消息消息总线持有的命令集合对象，供相应处理步骤使用。
     * @param subscriptions 当前Redis消息消息总线持有的订阅集合对象，供相应处理步骤使用。
     * @param keyPrefix 存储键前缀，用于区分本应用的数据与其他使用方。
     * @param replayTtl 回放保留时间的时间配置，供等待、调度或失效判断使用。
     */
    public RedisMessageBus(
            UnifiedJedis commands, JedisPool subscriptions, String keyPrefix, Duration replayTtl) {
        this(commands, subscriptions, keyPrefix, replayTtl, 256);
    }

    /** 每个慢订阅者最多保留此数量的消息，超过后关闭连接。 */
    public RedisMessageBus(
            UnifiedJedis commands,
            JedisPool subscriptions,
            String keyPrefix,
            Duration replayTtl,
            int subscriptionBufferCapacity) {
        requirePositive(subscriptionBufferCapacity, "subscriptionBufferCapacity");
        this.subscriptionBufferCapacity = subscriptionBufferCapacity;
        this.commands = Objects.requireNonNull(commands, "commands");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.keyPrefix = normalizePrefix(keyPrefix);
        Duration ttl = Objects.requireNonNull(replayTtl, "replayTtl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("replayTtl 必须为正数");
        }
        this.ttlSeconds = Math.max(1, ttl.toSeconds());
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @param payload 负载的索引映射，供按键查找或归并当前组件的数据。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.fromCallable(
                () -> {
                    String entryId = Long.toString(commands.incr(keyPrefix + "sequence"));
                    Map<String, Object> envelope = new LinkedHashMap<>();
                    envelope.put("entryId", entryId);
                    envelope.put("payload", payload);
                    String redisKey = queueKey(key);
                    commands.rpush(redisKey, toJson(envelope));
                    expire(redisKey);
                    return entryId;
                });
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @param maxCount 最大的数量，供运行统计或容量控制使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        requirePositive(maxCount, "maxCount");
        return Mono.fromCallable(
                () -> {
                    List<String> values =
                            scriptStrings(
                                    commands.eval(
                                            QUEUE_DRAIN_SCRIPT,
                                            List.of(queueKey(key)),
                                            List.of(Integer.toString(maxCount))));
                    if (values == null || values.isEmpty()) {
                        return List.of();
                    }
                    List<BusEntry> entries = new ArrayList<>(values.size());
                    for (String value : values) {
                        Map<String, Object> envelope = fromJson(value);
                        Object rawPayload = envelope.get("payload");
                        if (!(rawPayload instanceof Map<?, ?> map)) {
                            throw new IllegalStateException("Redis queue payload 不是对象");
                        }
                        entries.add(
                                new BusEntry(
                                        Objects.toString(envelope.get("entryId")),
                                        stringObjectMap(map)));
                    }
                    return List.copyOf(entries);
                });
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.fromRunnable(() -> commands.del(queueKey(key)));
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.fromCallable(() -> commands.llen(queueKey(key)) > 0);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @param payload 负载的索引映射，供按键查找或归并当前组件的数据。
     * @param maxLen 当前Redis消息消息总线使用的最大Len，供其处理与状态记录使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return logAppendBounded(key, payload, maxLen, 0, 0L);
    }

    /**
     * 追加一条回放记录，可按 UTF-8 字节数限制大小。限制检查与追加原子执行；{@code maxTotalBytes}
     * 要求日志仅追加，即 {@code maxLen == 0}。
     */
    public Mono<String> logAppendBounded(
            String key,
            Map<String, Object> payload,
            int maxLen,
            int maxEventBytes,
            long maxTotalBytes) {
        return logAppendBounded(key, payload, maxLen, maxEventBytes, maxTotalBytes, 0);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @param payload 负载的索引映射，供按键查找或归并当前组件的数据。
     * @param maxLen 当前Redis消息消息总线使用的最大Len，供其处理与状态记录使用。
     * @param maxEventBytes 最大事件的字节数，用于容量或传输限制。
     * @param maxTotalBytes 最大总计的字节数，用于容量或传输限制。
     * @param maxTotalEvents 当前Redis消息消息总线使用的最大总计事件集合，供其处理与状态记录使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Mono<String> logAppendBounded(
            String key,
            Map<String, Object> payload,
            int maxLen,
            int maxEventBytes,
            long maxTotalBytes,
            int maxTotalEvents) {
        if (maxLen < 0 || maxEventBytes < 0 || maxTotalBytes < 0L || maxTotalEvents < 0) {
            throw new IllegalArgumentException("Redis log 上限不能为负数");
        }
        if (maxLen > 0 && maxTotalBytes > 0L) {
            throw new IllegalArgumentException("字节受限的 Redis log 必须只追加");
        }
        return Mono.fromCallable(
                () -> {
                    String redisKey = logKey(key);
                    String json = toJson(payload);
                    int eventBytes = json.getBytes(StandardCharsets.UTF_8).length;
                    if (maxEventBytes > 0 && eventBytes > maxEventBytes) {
                        throw new IllegalStateException("Redis log 单事件超过字节上限");
                    }
                    Object sequence =
                            commands.eval(
                                    LOG_APPEND_SCRIPT,
                                    List.of(
                                            redisKey,
                                            logSequenceKey(key),
                                            logHeadSequenceKey(key),
                                            logDedupeKey(key),
                                            logBytesKey(key)),
                                    List.of(
                                            json,
                                            Integer.toString(maxLen),
                                            Long.toString(ttlSeconds),
                                            Integer.toString(maxEventBytes),
                                            Long.toString(maxTotalBytes),
                                            Integer.toString(maxTotalEvents)));
                    return Objects.toString(sequence);
                });
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @param since 当前Redis消息消息总线使用的since，供其处理与状态记录使用。
     * @param maxCount 最大的数量，供运行统计或容量控制使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        requirePositive(maxCount, "maxCount");
        return Mono.fromCallable(
                () -> {
                    long afterSequence = parseSequence(since);
                    long firstSequence = firstSequence(key);
                    long start =
                            afterSequence == 0L || firstSequence == 0L
                                    ? 0L
                                    : Math.max(0L, afterSequence - firstSequence + 1L);
                    List<String> values =
                            commands.lrange(logKey(key), start, start + maxCount - 1L);
                    if (values == null || values.isEmpty()) {
                        return List.of();
                    }
                    List<BusEntry> entries = new ArrayList<>(Math.min(values.size(), maxCount));
                    for (String value : values) {
                        Map<String, Object> envelope = fromJson(value);
                        long entrySequence =
                                parseSequence(Objects.toString(envelope.get("entryId"), ""));
                        if (entrySequence <= afterSequence) {
                            continue;
                        }
                        Object rawPayload = envelope.get("payload");
                        if (!(rawPayload instanceof Map<?, ?> map)) {
                            throw new IllegalStateException("Redis log payload 不是对象");
                        }
                        entries.add(
                                new BusEntry(Long.toString(entrySequence), stringObjectMap(map)));
                        if (entries.size() >= maxCount) {
                            break;
                        }
                    }
                    return List.copyOf(entries);
                });
    }

    /** 报告物理 Redis List 是否仍存在，用于恢复诊断。 */
    public Mono<Boolean> logExists(String key) {
        return Mono.fromCallable(() -> commands.exists(logKey(key)));
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.fromRunnable(
                () ->
                        commands.del(
                                logKey(key),
                                logSequenceKey(key),
                                logHeadSequenceKey(key),
                                logDedupeKey(key),
                                logBytesKey(key)));
    }

    /**
     * 发布Redis消息消息总线。
     *
     * @param key 当前对象的查找或写入键。
     * @param payload 负载的索引映射，供按键查找或归并当前组件的数据。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return Mono.fromRunnable(() -> commands.publish(channelKey(key), toJson(payload)));
    }

    /**
     * 订阅Redis消息消息总线。
     *
     * @param key 当前对象的查找或写入键。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        String channel = channelKey(key);
        return Flux.<Map<String, Object>>create(
                        sink -> subscribe(channel, sink), FluxSink.OverflowStrategy.ERROR)
                .subscribeOn(Schedulers.boundedElastic(), false)
                .onBackpressureBuffer(subscriptionBufferCapacity);
    }

    /**
     * 订阅Redis消息消息总线。
     *
     * @param channel 当前Redis消息消息总线使用的通道，供其处理与状态记录使用。
     * @param sink 上报端的索引映射，供按键查找或归并当前组件的数据。
     */
    private void subscribe(String channel, FluxSink<Map<String, Object>> sink) {
        AtomicReference<JedisPubSub> listenerRef = new AtomicReference<>();
        AtomicReference<Jedis> connectionRef = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        Runnable stop =
                () -> {
                    cancelled.set(true);
                    JedisPubSub listener = listenerRef.get();
                    try {
                        if (listener != null && listener.isSubscribed()) listener.unsubscribe();
                    } finally {
                        Jedis connection = connectionRef.getAndSet(null);
                        if (connection != null) connection.close();
                    }
                };
        sink.onCancel(stop::run);
        sink.onDispose(stop::run);

        try {
            Jedis connection = subscriptions.getResource();
            connectionRef.set(connection);
            JedisPubSub listener =
                    new JedisPubSub() {
                        /**
                         * 响应消息。
                         *
                         * @param ignored 当前匿名实现使用的ignored，供其处理与状态记录使用。
                         * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
                         */
                        @Override
                        public void onMessage(String ignored, String message) {
                            if (!cancelled.get()) {
                                sink.next(fromJson(message));
                            }
                        }
                    };
            listenerRef.set(listener);
            if (cancelled.get()) {
                stop.run();
                return;
            }
            connection.subscribe(listener, channel);
            if (!cancelled.get()) {
                sink.complete();
            }
        } catch (Exception error) {
            if (!cancelled.get()) {
                sink.error(error);
            }
        } finally {
            stop.run();
        }
    }

    /**
     * 完成当前操作的expire步骤，按实现更新相应状态或依赖。
     *
     * @param key 当前对象的查找或写入键。
     */
    private void expire(String key) {
        commands.expire(key, ttlSeconds);
    }

    /**
     * 生成当前操作所需的queueKey文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private String queueKey(String key) {
        return keyPrefix + "queue:" + key;
    }

    /**
     * 生成当前操作所需的logKey文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private String logKey(String key) {
        return keyPrefix + "log:" + key;
    }

    /**
     * 生成当前操作所需的logSequenceKey文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private String logSequenceKey(String key) {
        return keyPrefix + "log-sequence:" + key;
    }

    /**
     * 生成当前操作所需的logHeadSequenceKey文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private String logHeadSequenceKey(String key) {
        return keyPrefix + "log-head-sequence:" + key;
    }

    /**
     * 生成当前操作所需的logDedupeKey文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private String logDedupeKey(String key) {
        return keyPrefix + "log-dedupe:" + key;
    }

    /**
     * 生成当前操作所需的logBytesKey文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private String logBytesKey(String key) {
        return keyPrefix + "log-bytes:" + key;
    }

    /**
     * 生成当前操作所需的channelKey文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private String channelKey(String key) {
        return keyPrefix + "channel:" + key;
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次操作返回的长整型结果。
     */
    private long firstSequence(String key) {
        long cached = parseSequence(commands.get(logHeadSequenceKey(key)));
        if (cached > 0L) {
            return cached;
        }
        String first = commands.lindex(logKey(key), 0L);
        if (first == null) {
            return 0L;
        }
        return parseSequence(Objects.toString(fromJson(first).get("entryId"), ""));
    }

    /**
     * 规范化前缀。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String normalizePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "horizen-agent:bus:";
        }
        return value.endsWith(":") ? value : value + ":";
    }

    /**
     * 取得并校验正值。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name 需要定位或处理的名称。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
    }

    /**
     * 解析序号。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的长整型结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            long sequence = Long.parseLong(value);
            if (sequence < 0L) {
                throw new IllegalArgumentException("Redis event sequence 不能为负数");
            }
            return sequence;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Redis event sequence 无效", error);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理得到的结果集合。
     */
    private static List<String> scriptStrings(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            result.add(Objects.toString(item));
        }
        return List.copyOf(result);
    }

    /**
     * 转换为JSON。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String toJson(Object value) {
        return JsonUtils.getJsonCodec().toJson(value);
    }

    /**
     * 从输入构造JSON。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 按返回类型约定组织的结果映射。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fromJson(String value) {
        return JsonUtils.getJsonCodec().fromJson(value, Map.class);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisMessageBus处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 按返回类型约定组织的结果映射。
     */
    private static Map<String, Object> stringObjectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(Objects.toString(key), item));
        return result;
    }
}
