package dev.horizen.agent.observability.horizen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;

import lombok.Getter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 向 Horizen 批量接口导出数据，使用容量受限的队列和单个工作线程。 */
public final class HorizenHttpBatchExporter implements HorizenTraceBatchSink, AutoCloseable {
    /** 当前组件的诊断日志器。 */
    private static final Logger log = LoggerFactory.getLogger(HorizenHttpBatchExporter.class);

    /** 批次路径使用的固定标识或协议文本。 */
    private static final String BATCH_PATH = "/api/v1/sdk/traces/batch";

    /** 当前组件的配置与策略参数。 */
    private final HorizenTraceConfig config;

    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final ObjectMapper mapper;

    /** 当前适配器使用的远端客户端，供实际网络或服务请求使用。 */
    private final HttpClient client;

    /** 已解析的服务接口地址，供实际网络请求使用。 */
    @Getter private final URI endpoint;

    /** 待处理工作队列，供异步消费者按接收顺序处理。 */
    private final ArrayBlockingQueue<HorizenTraceBatch> queue;

    /** 组件是否已关闭，用于避免重复释放或继续接收新工作。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 尚未完成处理的工作或计数，供刷新、关闭与容量控制使用。 */
    private final AtomicLong pending = new AtomicLong();

    /** stopping的原子状态，供并发更新与统计读取使用。 */
    private final AtomicBoolean stopping = new AtomicBoolean();

    /** 重试次数的原子状态，供并发更新与统计读取使用。 */
    private final AtomicLong retried = new AtomicLong();

    /** 上报成功数的原子状态，供并发更新与统计读取使用。 */
    private final AtomicLong uploaded = new AtomicLong();

    /** 失败的原子状态，供并发更新与统计读取使用。 */
    private final AtomicLong failed = new AtomicLong();

    /** 丢弃数的原子状态，供并发更新与统计读取使用。 */
    private final AtomicLong dropped = new AtomicLong();

    /** 最近一次失败的诊断摘要；未记录失败时为空。 */
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    /** 负责异步处理当前队列或执行段的工作线程。 */
    private final Thread worker;

    /**
     * 创建HorizenHTTP批次上报器，初始化该组件所需的状态、配置或依赖。
     *
     * @param config 当前组件的配置与策略参数。
     */
    public HorizenHttpBatchExporter(HorizenTraceConfig config) {
        this(
                config,
                JsonUtils.newMapper(),
                HttpClient.newBuilder().connectTimeout(config.getRequestTimeout()).build());
    }

    /**
     * 创建HorizenHTTP批次上报器，初始化该组件所需的状态、配置或依赖。
     *
     * @param config 当前组件的配置与策略参数。
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param client 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    public HorizenHttpBatchExporter(
            HorizenTraceConfig config, ObjectMapper mapper, HttpClient client) {
        this.config = Objects.requireNonNull(config, "config");
        config.validateRetryPolicy();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = endpoint(config.getBaseUrl(), BATCH_PATH);
        this.queue = new ArrayBlockingQueue<>(config.getQueueCapacity());
        this.worker = new Thread(this::run, "horizen-trace-exporter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 尝试把 Trace 批次包放入有界队列；容量不足时优先保留终态，并累计被丢弃的包。
     *
     * @param batch 当前HorizenHTTP批次上报器持有的批次对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public synchronized boolean submit(HorizenTraceBatch batch) {
        if (batch == null || closed.get()) {
            dropped.incrementAndGet();
            return false;
        }
        pending.incrementAndGet();
        boolean accepted = queue.offer(batch);
        if (!accepted) {
            // 终态信封比旧的 RUNNING 快照更能反映当前结果。
            if (!"RUNNING".equals(batch.getTrace().getStatus()) && queue.poll() != null) {
                dropped.incrementAndGet();
                pending.decrementAndGet();
                accepted = queue.offer(batch);
            }
            if (!accepted) dropped.incrementAndGet();
        }
        if (!accepted) pending.decrementAndGet();
        return accepted;
    }

    /**
     * 在给定时限内等待待处理计数归零；中断或超过时限时返回未完成。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param timeout 本次等待允许持续的最长时间。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() != 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return pending.get() == 0;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenHttpBatchExporter处理步骤使用。
     *
     * @return 本次操作返回的长整型结果。
     */
    public long retriedCount() {
        return retried.get();
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenHttpBatchExporter处理步骤使用。
     *
     * @return 本次操作返回的长整型结果。
     */
    public long uploadedCount() {
        return uploaded.get();
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenHttpBatchExporter处理步骤使用。
     *
     * @return 本次操作返回的长整型结果。
     */
    public long failedCount() {
        return failed.get();
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenHttpBatchExporter处理步骤使用。
     *
     * @return 本次操作返回的长整型结果。
     */
    public long droppedCount() {
        return dropped.get();
    }

    /** 安全的诊断摘要，不包含请求头或响应体。 */
    public String lastFailure() {
        return lastFailure.get();
    }

    /** 消费队列并按 Trace 归并最新批次包，发送后归还在途计数；停止信号终止后台消费者。 */
    private void run() {
        while (!stopping.get() && (!closed.get() || pending.get() != 0)) {
            try {
                HorizenTraceBatch batch = queue.poll(100, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    int consumed = 1;
                    try {
                        if (!config.getFlushInterval().isZero()) {
                            Thread.sleep(config.getFlushInterval().toMillis());
                        }
                        List<HorizenTraceBatch> drained = new ArrayList<>();
                        queue.drainTo(drained);
                        consumed += drained.size();
                        Map<String, HorizenTraceBatch> latestByTrace = new LinkedHashMap<>();
                        latestByTrace.put(batch.getTrace().getTraceId(), batch);
                        for (HorizenTraceBatch candidate : drained) {
                            latestByTrace.put(candidate.getTrace().getTraceId(), candidate);
                        }
                        latestByTrace.values().forEach(this::send);
                    } catch (InterruptedException interrupted) {
                        dropped.addAndGet(consumed);
                        throw interrupted;
                    } finally {
                        pending.addAndGet(-consumed);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException unexpected) {
                failed.incrementAndGet();
                log.debug("Horizen trace exporter failed", unexpected);
            }
        }
    }

    /**
     * 发送HorizenHTTP批次上报器。
     *
     * @param batch 当前HorizenHTTP批次上报器持有的批次对象，供相应处理步骤使用。
     */
    private void send(HorizenTraceBatch batch) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(batch);
        } catch (Exception error) {
            failed.incrementAndGet();
            lastFailure.set(error.getClass().getSimpleName());
            return;
        }
        for (int attempt = 0; attempt <= config.getMaxRetries(); attempt++) {
            if (stopping.get()) {
                dropped.incrementAndGet();
                return;
            }
            boolean retryable = false;
            long retryAfterMs = 0;
            try {
                HttpRequest.Builder request =
                        HttpRequest.newBuilder(endpoint)
                                .timeout(config.getRequestTimeout())
                                .header("Accept", "application/json")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                if (config.getAuthToken() != null)
                    request.header("Authorization", "Bearer " + config.getAuthToken());
                HttpResponse<byte[]> response =
                        client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300 && successfulEnvelope(response.body())) {
                    uploaded.incrementAndGet();
                    lastFailure.set(null);
                    return;
                }
                if (status >= 200 && status < 300) {
                    lastFailure.set(businessFailure(response.body()));
                    // 仅重试已识别的暂时性业务错误。
                    retryable =
                            "BUSINESS 429".equals(lastFailure.get())
                                    || "BUSINESS 503".equals(lastFailure.get())
                                    || "BUSINESS 500".equals(lastFailure.get());
                } else {
                    lastFailure.set("HTTP " + status);
                    retryable = status == 429 || status >= 500;
                }
                try {
                    retryAfterMs =
                            Long.parseLong(response.headers().firstValue("Retry-After").orElse("0"))
                                    * 1000;
                } catch (NumberFormatException ignored) {
                    /** 不支持的日期格式也应采用退避策略。 */
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                dropped.incrementAndGet();
                return;
            } catch (IOException error) {
                lastFailure.set(error.getClass().getSimpleName());
                retryable = true;
            } catch (RuntimeException error) {
                lastFailure.set(error.getClass().getSimpleName());
            }
            if (!retryable || attempt == config.getMaxRetries()) {
                failed.incrementAndGet();
                log.debug("Horizen trace upload failed: {}", lastFailure.get());
                return;
            }
            long delay =
                    Math.min(
                            config.getRetryMaxDelay().toMillis(),
                            Math.max(
                                    retryAfterMs,
                                    config.getRetryInitialDelay().toMillis() * (1L << attempt)));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                dropped.incrementAndGet();
                return;
            }
            retried.incrementAndGet();
        }
    }

    /**
     * 检查successfulEnvelope对应的条件，供调用方选择后续处理分支。
     *
     * @param body 当前HorizenHTTP批次上报器持有的正文对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private boolean successfulEnvelope(byte[] body) {
        try {
            JsonNode envelope = mapper.readTree(body);
            return envelope != null && envelope.path("code").asInt(Integer.MIN_VALUE) == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 生成当前操作所需的businessFailure文本，供调用方继续处理。
     *
     * @param body 当前HorizenHTTP批次上报器持有的正文对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private String businessFailure(byte[] body) {
        try {
            JsonNode envelope = mapper.readTree(body);
            if (envelope != null && envelope.has("code")) {
                return "BUSINESS " + envelope.path("code").asInt();
            }
        } catch (Exception ignored) {
            // 诊断信息不包含响应体，避免暴露其中的敏感内容。
        }
        return "INVALID_RESPONSE";
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
        }
        long deadline = System.nanoTime() + config.getShutdownTimeout().toNanos();
        flush(config.getShutdownTimeout());
        stopping.set(true);
        worker.interrupt();
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime()));
        if (remainingMs > 0) {
            try {
                worker.join(remainingMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            List<HorizenTraceBatch> remaining = new ArrayList<>();
            queue.drainTo(remaining);
            dropped.addAndGet(remaining.size());
            pending.addAndGet(-remaining.size());
        }
    }

    /**
     * 读取接口地址的当前值。
     *
     * @param baseUrl 远端服务的基础地址，用于拼接接口路径。
     * @param path 需要读取、写入或校验的路径。
     * @return {@link #endpoint} 中保存的值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static URI endpoint(URI baseUrl, String path) {
        String basePath = baseUrl.getRawPath() == null ? "" : baseUrl.getRawPath();
        String fullPath =
                basePath.endsWith("/")
                        ? basePath.substring(0, basePath.length() - 1) + path
                        : basePath + path;
        String query = baseUrl.getRawQuery() == null ? "" : "?" + baseUrl.getRawQuery();
        try {
            return URI.create(
                    baseUrl.getScheme() + "://" + baseUrl.getRawAuthority() + fullPath + query);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Cannot build Horizen batch endpoint", error);
        }
    }
}
