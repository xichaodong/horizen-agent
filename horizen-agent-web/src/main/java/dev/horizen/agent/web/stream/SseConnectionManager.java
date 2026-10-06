package dev.horizen.agent.web.stream;

import dev.horizen.agent.web.api.chat.ChatApi.ChatStreamEvent;
import dev.horizen.agent.web.config.SseProperties;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * 管理 SSE 连接限制、心跳、背压缓冲和写入线程。
 */
public final class SseConnectionManager implements AutoCloseable {
    /**
     * 所有权Executors的状态标记，用于选择当前组件的处理路径。
     */
    private final boolean ownsExecutors;

    /**
     * 连接的去重集合，供成员查找或范围检查使用。
     */
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

    /**
     * stopped的原子状态，供并发更新与统计读取使用。
     */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /**
     * 宿主绑定的配置对象，供组件组装与策略校验使用。
     */
    private final SseProperties properties;

    /**
     * 当前仍处于观察状态的 SSE 连接数。
     */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * 当前 SSE 连接队列中等待发送的事件数量。
     */
    private final AtomicInteger queuedEvents = new AtomicInteger();

    /**
     * 当前 SSE 连接队列中等待发送的内容字节数。
     */
    private final AtomicLong queuedBytes = new AtomicLong();

    /**
     * 观察期间 SSE 待发送内容字节数的峰值。
     */
    private final AtomicLong peakQueuedBytes = new AtomicLong();

    /**
     * 自本实例启动以来接收的 SSE 连接总数。
     */
    private final LongAdder totalConnections = new LongAdder();

    /**
     * 因连接容量或准入条件被拒绝的 SSE 连接累计次数。
     */
    private final LongAdder rejectedConnections = new LongAdder();

    /**
     * 自本实例启动以来正常结束的 SSE 连接次数。
     */
    private final LongAdder normalCloses = new LongAdder();

    /**
     * 因写入或连接错误而结束的 SSE 连接累计次数。
     */
    private final LongAdder errorCloses = new LongAdder();

    /**
     * 因客户端消费过慢而结束的 SSE 连接累计次数。
     */
    private final LongAdder slowClientCloses = new LongAdder();

    /**
     * 因观察连接时限到期而结束的 SSE 连接累计次数。
     */
    private final LongAdder timeoutCloses = new LongAdder();

    /**
     * 因浏览器或网络断开而结束的 SSE 连接累计次数。
     */
    private final LongAdder disconnectedCloses = new LongAdder();

    /**
     * 按间隔发送 SSE 保活信息的调度资源。
     */
    private final ScheduledExecutorService heartbeats;

    /**
     * 处理 SSE 发送工作的有界线程池。
     */
    private final ThreadPoolExecutor writers;

    /**
     * 创建SSE连接管理器，初始化该组件所需的状态、配置或依赖。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     */
    public SseConnectionManager(SseProperties properties) {
        this.ownsExecutors = true;
        this.properties = Objects.requireNonNull(properties, "properties");
        properties.validate();
        this.heartbeats =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            Thread thread = new Thread(task, "horizen-sse-heartbeat");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.writers =
                new ThreadPoolExecutor(
                        properties.getWriterCoreThreads(),
                        properties.getWriterMaxThreads(),
                        60L,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(properties.getWriterQueueCapacity()),
                        task -> {
                            Thread thread = new Thread(task, "horizen-sse-writer");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 创建SSE连接管理器，初始化该组件所需的状态、配置或依赖。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param heartbeats 提供heartbeats能力的依赖，具体实现由当前组件的组装方传入。
     * @param writers    当前SSE连接管理器持有的writers对象，供相应处理步骤使用。
     */
    public SseConnectionManager(
            SseProperties properties,
            ScheduledExecutorService heartbeats,
            ThreadPoolExecutor writers) {
        this.properties = Objects.requireNonNull(properties);
        properties.validate();
        this.heartbeats = Objects.requireNonNull(heartbeats);
        this.writers = Objects.requireNonNull(writers);
        this.ownsExecutors = false;
    }

    /**
     * 创建一个只负责观察的 SSE 连接，建立有界发送队列与关闭处理。
     *
     * @param events  当前执行或历史事件集合，供持久化、回放与观测使用。
     * @param emitter 当前SSE连接管理器持有的发送器对象，供相应处理步骤使用。
     * @return 本次操作返回的SSE发送器结果。
     * @throws ResponseStatusException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SseEmitter open(Flux<ChatStreamEvent> events, SseEmitter emitter) {
        if (stopped.get())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SSE 服务正在关闭");
        if (activeConnections.incrementAndGet() > properties.getMaxConnections()) {
            activeConnections.decrementAndGet();
            rejectedConnections.increment();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "SSE 连接数已达上限");
        }
        totalConnections.increment();
        Connection connection = new Connection(emitter, activeConnections::decrementAndGet);
        connections.add(connection);
        try {
            connection.start(events);
        } catch (RuntimeException error) {
            connection.fail(error);
            throw error;
        }
        if (stopped.get()) connection.cleanup(CloseReason.DISCONNECTED);
        return emitter;
    }

    /**
     * 读取连接、队列与写入线程池的当前资源统计。
     *
     * @return 本次操作返回的快照结果。
     */
    public Snapshot snapshot() {
        return new Snapshot(
                activeConnections.get(),
                totalConnections.sum(),
                rejectedConnections.sum(),
                queuedEvents.get(),
                queuedBytes.get(),
                peakQueuedBytes.get(),
                writers.getActiveCount(),
                writers.getPoolSize(),
                writers.getQueue().size(),
                normalCloses.sum(),
                errorCloses.sum(),
                slowClientCloses.sum(),
                timeoutCloses.sum(),
                disconnectedCloses.sum());
    }

    /**
     * 关闭当前管理器持有的 SSE 观察连接与写入资源。
     * 并发状态更新包含比较交换操作。
     */
    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) return;
        for (Connection connection : connections) {
            connection.cleanup(CloseReason.DISCONNECTED);
            try {
                connection.emitter.complete();
            } catch (RuntimeException ignored) {
            }
        }
        if (ownsExecutors) {
            heartbeats.shutdownNow();
            writers.shutdownNow();
        }
    }

    /**
     * 一个浏览器观察连接的队列、写入与关闭状态。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private final class Connection {
        /**
         * 单个 HTTP 观察连接的 SSE 发送器。
         */
        private final SseEmitter emitter;

        /**
         * 连接关闭后归还本实例连接容量的回调。
         */
        private final Runnable releaseConnection;

        /**
         * 当前响应或事件输入流的订阅，取消时终止后续数据接收。
         */
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();

        /**
         * 心跳的原子状态，供并发更新与统计读取使用。
         */
        private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        /**
         * 组件是否已关闭，用于避免重复释放或继续接收新工作。
         */
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * draining的原子状态，供并发更新与统计读取使用。
         */
        private final AtomicBoolean draining = new AtomicBoolean();

        /**
         * 心跳待发送的原子状态，供并发更新与统计读取使用。
         */
        private final AtomicBoolean heartbeatQueued = new AtomicBoolean();

        /**
         * upstream完成的原子状态，供并发更新与统计读取使用。
         */
        private final AtomicBoolean upstreamCompleted = new AtomicBoolean();

        /**
         * 连接Released的原子状态，供并发更新与统计读取使用。
         */
        private final AtomicBoolean connectionReleased = new AtomicBoolean();

        /**
         * 待处理的字节数，用于容量或传输限制。
         */
        private final AtomicInteger pendingBytes = new AtomicInteger();

        /**
         * outbound的待处理队列，由当前组件的消费者取出处理。
         */
        private final ArrayBlockingQueue<PendingEvent> outbound =
                new ArrayBlockingQueue<>(properties.getOutboundMaxEvents());

        /**
         * 启动连接。
         * 并发状态更新包含比较交换操作。
         *
         * @param events 当前执行或历史事件集合，供持久化、回放与观测使用。
         */
        private void start(Flux<ChatStreamEvent> events) {
            emitter.onCompletion(() -> cleanup(CloseReason.DISCONNECTED));
            emitter.onTimeout(this::timeoutNow);
            emitter.onError(ignored -> cleanup(CloseReason.ERROR));
            heartbeat.set(
                    heartbeats.scheduleAtFixedRate(
                            this::sendHeartbeat,
                            properties.getHeartbeatInterval().toMillis(),
                            properties.getHeartbeatInterval().toMillis(),
                            TimeUnit.MILLISECONDS));
            Disposable current =
                    events.subscribe(this::enqueue, this::fail, this::finishAfterDrain);
            if (!subscription.compareAndSet(null, current)) current.dispose();
            if (closed.get()) cleanup(CloseReason.DISCONNECTED);
        }

        /**
         * 完成当前操作的enqueue步骤，按实现更新相应状态或依赖。
         * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
         *
         * @param event 当前连接持有的事件对象，供相应处理步骤使用。
         */
        private void enqueue(ChatStreamEvent event) {
            if (closed.get()) return;
            int bytes = eventSize(event);
            if (bytes > properties.getOutboundMaxBytes()) {
                failSlow(new IllegalStateException("SSE 事件超过单连接发送上限"));
                return;
            }
            if (pendingBytes.addAndGet(bytes) > properties.getOutboundMaxBytes()) {
                pendingBytes.addAndGet(-bytes);
                failSlow(new IllegalStateException("SSE 客户端消费过慢"));
                return;
            }
            PendingEvent pending = new PendingEvent(event, bytes, System.nanoTime());
            if (!outbound.offer(pending)) {
                pendingBytes.addAndGet(-bytes);
                failSlow(new IllegalStateException("SSE 客户端消费过慢"));
                return;
            }
            recordQueued(bytes);
            if (closed.get() && outbound.remove(pending)) {
                pendingBytes.addAndGet(-bytes);
                releaseQueued(bytes);
                return;
            }
            scheduleDrain();
        }

        /**
         * 完成当前操作的scheduleDrain步骤，按实现更新相应状态或依赖。
         * 并发状态更新包含比较交换操作。
         */
        private void scheduleDrain() {
            if (!draining.compareAndSet(false, true)) return;
            try {
                writers.execute(this::drain);
            } catch (RuntimeException error) {
                draining.set(false);
                fail(error);
            }
        }

        /**
         * 取出待处理的连接。
         * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
         */
        private void drain() {
            try {
                while (!closed.get()) {
                    PendingEvent pending = outbound.poll();
                    if (pending == null) break;
                    pendingBytes.addAndGet(-pending.bytes);
                    releaseQueued(pending.bytes);
                    if (System.nanoTime() - pending.enqueuedAtNanos
                            > properties.getMaxQueueWait().toNanos()) {
                        failSlow(new IllegalStateException("SSE 客户端消费过慢"));
                        return;
                    }
                    if (pending.event == null) {
                        heartbeatQueued.set(false);
                        writeHeartbeat();
                    } else {
                        writeEvent(pending.event);
                    }
                }
            } finally {
                draining.set(false);
            }
            if (closed.get()) return;
            if (!outbound.isEmpty()) scheduleDrain();
            else if (upstreamCompleted.get()) completeNow();
        }

        /**
         * 写入事件。
         *
         * @param event 当前连接持有的事件对象，供相应处理步骤使用。
         */
        private synchronized void writeEvent(ChatStreamEvent event) {
            if (closed.get()) return;
            try {
                SseEmitter.SseEventBuilder value =
                        SseEmitter.event().name(event.getType()).data(event);
                if (event.getStreamSequence() != null) {
                    value.id(Long.toString(event.getStreamSequence()));
                }
                emitter.send(value);
            } catch (IOException | RuntimeException error) {
                fail(error);
            }
        }

        /**
         * 发送心跳。
         * 并发状态更新包含比较交换操作。
         * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
         */
        private void sendHeartbeat() {
            if (closed.get() || !heartbeatQueued.compareAndSet(false, true)) return;
            PendingEvent pending = new PendingEvent(null, 0, System.nanoTime());
            if (!outbound.offer(pending)) {
                heartbeatQueued.set(false);
                return;
            }
            recordQueued(0);
            if (closed.get() && outbound.remove(pending)) {
                releaseQueued(0);
                heartbeatQueued.set(false);
                return;
            }
            scheduleDrain();
        }

        /**
         * 写入心跳。
         */
        private synchronized void writeHeartbeat() {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("keep-alive").data(""));
            } catch (IOException | RuntimeException error) {
                fail(error);
            }
        }

        /**
         * 收敛失败的连接。
         *
         * @param error 本次失败的异常，用于分类、传播或诊断。
         */
        private void fail(Throwable error) {
            if (!closeOnce(CloseReason.ERROR)) return;
            disposeResources();
            try {
                emitter.completeWithError(error);
            } catch (RuntimeException ignored) {
            }
        }

        /**
         * 收敛失败的慢速。
         *
         * @param error 本次失败的异常，用于分类、传播或诊断。
         */
        private void failSlow(Throwable error) {
            if (!closeOnce(CloseReason.SLOW_CLIENT)) return;
            disposeResources();
            try {
                emitter.completeWithError(error);
            } catch (RuntimeException ignored) {
            }
        }

        /**
         * 收敛处理后排空。
         */
        private void finishAfterDrain() {
            upstreamCompleted.set(true);
            if (outbound.isEmpty() && !draining.get()) completeNow();
            else scheduleDrain();
        }

        /**
         * 完成当前时间。
         */
        private void completeNow() {
            if (!closeOnce(CloseReason.NORMAL)) return;
            disposeResources();
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
            }
        }

        /**
         * 完成当前操作的timeoutNow步骤，按实现更新相应状态或依赖。
         */
        private void timeoutNow() {
            if (!closeOnce(CloseReason.TIMEOUT)) return;
            disposeResources();
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
            }
        }

        /**
         * 完成当前操作的cleanup步骤，按实现更新相应状态或依赖。
         *
         * @param reason 当前连接持有的原因对象，供相应处理步骤使用。
         */
        private void cleanup(CloseReason reason) {
            if (closeOnce(reason)) disposeResources();
        }

        /**
         * 关闭Once。
         * 并发状态更新包含比较交换操作。
         *
         * @param reason 当前连接持有的原因对象，供相应处理步骤使用。
         * @return 本次检查是否通过或本次更新是否成功。
         */
        private boolean closeOnce(CloseReason reason) {
            if (!closed.compareAndSet(false, true)) return false;
            connections.remove(this);
            switch (reason) {
                case NORMAL -> normalCloses.increment();
                case ERROR -> errorCloses.increment();
                case SLOW_CLIENT -> slowClientCloses.increment();
                case TIMEOUT -> timeoutCloses.increment();
                case DISCONNECTED -> disconnectedCloses.increment();
            }
            return true;
        }

        /**
         * 完成当前操作的disposeResources步骤，按实现更新相应状态或依赖。
         * 并发状态更新包含比较交换操作。
         */
        private void disposeResources() {
            Disposable current = subscription.getAndSet(null);
            if (current != null && !current.isDisposed()) current.dispose();
            ScheduledFuture<?> scheduled = heartbeat.getAndSet(null);
            if (scheduled != null) scheduled.cancel(false);
            PendingEvent queued;
            while ((queued = outbound.poll()) != null) releaseQueued(queued.bytes);
            pendingBytes.set(0);
            if (connectionReleased.compareAndSet(false, true)) releaseConnection.run();
        }
    }

    /**
     * 记录待发送。
     *
     * @param bytes 当前操作处理的内容字节。
     */
    private void recordQueued(int bytes) {
        queuedEvents.incrementAndGet();
        long total = queuedBytes.addAndGet(bytes);
        peakQueuedBytes.accumulateAndGet(total, Math::max);
    }

    /**
     * 释放待发送。
     *
     * @param bytes 当前操作处理的内容字节。
     */
    private void releaseQueued(int bytes) {
        queuedEvents.decrementAndGet();
        queuedBytes.addAndGet(-bytes);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SseConnectionManager处理步骤使用。
     *
     * @param event 当前SSE连接管理器持有的事件对象，供相应处理步骤使用。
     * @return 本次操作返回的整数结果。
     */
    private static int eventSize(ChatStreamEvent event) {
        return 128
                + textSize(event.getText())
                + textSize(event.getDetails())
                + textSize(event.getTitle());
    }

    /**
     * 计算或取得本方法声明的结果，供当前SseConnectionManager处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的整数结果。
     */
    private static int textSize(String value) {
        return value == null ? 0 : value.length() * 2;
    }

    /**
     * 本实例 SSE 连接与发送队列的资源统计快照。
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    @Getter
    public static final class Snapshot {
        /**
         * 当前仍处于观察状态的 SSE 连接数。
         */
        private final int activeConnections;

        /**
         * 自本实例启动以来接收的 SSE 连接总数。
         */
        private final long totalConnections;

        /**
         * 因连接容量或准入条件被拒绝的 SSE 连接累计次数。
         */
        private final long rejectedConnections;

        /**
         * 当前 SSE 连接队列中等待发送的事件数量。
         */
        private final int queuedEvents;

        /**
         * 当前 SSE 连接队列中等待发送的内容字节数。
         */
        private final long queuedBytes;

        /**
         * 观察期间 SSE 待发送内容字节数的峰值。
         */
        private final long peakQueuedBytes;

        /**
         * SSE 写入线程池当前正在执行工作的线程数。
         */
        private final int writerActiveThreads;

        /**
         * SSE 写入线程池当前保留的线程数。
         */
        private final int writerPoolSize;

        /**
         * SSE 写入线程池尚未开始处理的任务数。
         */
        private final int writerQueueSize;

        /**
         * 自本实例启动以来正常结束的 SSE 连接次数。
         */
        private final long normalCloses;

        /**
         * 因写入或连接错误而结束的 SSE 连接累计次数。
         */
        private final long errorCloses;

        /**
         * 因客户端消费过慢而结束的 SSE 连接累计次数。
         */
        private final long slowClientCloses;

        /**
         * 因观察连接时限到期而结束的 SSE 连接累计次数。
         */
        private final long timeoutCloses;

        /**
         * 因浏览器或网络断开而结束的 SSE 连接累计次数。
         */
        private final long disconnectedCloses;
    }

    /**
     * SSE 观察连接的关闭原因分类。
     */
    private enum CloseReason {
        /**
         * 观察连接正常结束。
         */
        NORMAL,
        /**
         * 观察连接因发送或处理异常而关闭。
         */
        ERROR,
        /**
         * 客户端消费过慢，超过连接发送队列的限制。
         */
        SLOW_CLIENT,
        /**
         * 观察连接超过允许的持续或等待时间。
         */
        TIMEOUT,
        /**
         * 浏览器或网络连接已断开。
         */
        DISCONNECTED
    }

    /**
     * 单个 SSE 连接队列中等待发送的事件及其容量信息。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class PendingEvent {
        /**
         * 当前记录保存的执行事件或已转换的发送事件。
         */
        private final ChatStreamEvent event;

        /**
         * 当前内容字节或字节计数，用于传输、校验与容量控制。
         */
        private final int bytes;

        /**
         * 事件进入发送队列时的单调时钟值，供计算排队耗时。
         */
        private final long enqueuedAtNanos;
    }
}
