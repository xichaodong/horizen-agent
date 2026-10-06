package dev.horizen.agent.application.turn;

import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.SessionExecutionState;

import lombok.Value;

import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 在服务端持有 Turn 执行；页面 SSE 连接只订阅事件，不拥有执行生命周期。
 */
public final class TurnExecutionManager implements AutoCloseable {
    /**
     * 当前组件的诊断日志器。
     */
    private static final Logger log = LoggerFactory.getLogger(TurnExecutionManager.class);

    /**
     * 默认回放事件集合的固定取值，用于相应策略和边界判断。
     */
    private static final int DEFAULT_REPLAY_EVENTS = 2048;

    /**
     * 启动注册超时的固定取值，用于相应策略和边界判断。
     */
    private static final Duration START_REGISTRATION_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 本组件使用的 {@code Object} 状态或依赖，用于 lifecycle 的处理。
     */
    private final Object lifecycle = new Object();

    /**
     * 组件是否已关闭，用于避免重复释放或继续接收新工作。
     */
    private volatile boolean closed;

    /**
     * 独立的阻塞安全控制池；事件写入占满共享工作线程时仍可中断 Runtime。
     */
    private final Scheduler controls = Schedulers.newBoundedElastic(4, 256, "turn-control");

    /**
     * 执行 Agent 模型与工具循环的运行时接口。
     */
    private final AgentRuntime runtime;

    /**
     * 本地观察流允许保留的回放事件数量。
     */
    private final int replayEvents;

    /**
     * 执行集合的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<SessionKey, ManagedTurn> turns = new ConcurrentHashMap<>();

    /**
     * 创建执行执行管理器，初始化该组件所需的状态、配置或依赖。
     *
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     */
    public TurnExecutionManager(AgentRuntime runtime) {
        this(runtime, DEFAULT_REPLAY_EVENTS);
    }

    /**
     * 创建执行执行管理器，初始化该组件所需的状态、配置或依赖。
     *
     * @param runtime      执行 Agent 模型与工具循环的运行时接口。
     * @param replayEvents 当前执行执行管理器使用的回放事件集合，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public TurnExecutionManager(AgentRuntime runtime, int replayEvents) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (replayEvents <= 0) {
            throw new IllegalArgumentException("replayEvents 必须为正数");
        }
        this.replayEvents = replayEvents;
    }

    /**
     * 取得本地执行句柄并持有运行订阅，使浏览器观察连接的断开不终止实际执行。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param request     当前操作的请求参数。
     * @param source      待解析或转换的来源对象。
     * @param maxDuration 最大耗时的时间配置，供等待、调度或失效判断使用。
     * @param observer    接收结果或事件的回调。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws ClosedException             当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalArgumentException    当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws TurnAlreadyRunningException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Flux<AgentRuntimeEvent> start(
            AgentTurnRequest request,
            Flux<AgentRuntimeEvent> source,
            Duration maxDuration,
            Consumer<AgentRuntimeEvent> observer) {
        return start(request, source, maxDuration, observer, (event, error) -> {
        });
    }

    /**
     * 持有单次执行，并在取得失败终态后调用写入失败收敛器。
     *
     * @param request         当前执行请求。
     * @param source          借用的执行事件流；本管理器拥有其订阅。
     * @param maxDuration     执行的总时间预算。
     * @param observer        按顺序执行的观测或持久化回调，不在状态锁内调用。
     * @param observerFailure 仅由取得终态的写入失败调用，防止覆盖已接受的取消或超时。
     * @return 按持久化顺序输出的本地观察流。
     */
    public Flux<AgentRuntimeEvent> start(
            AgentTurnRequest request,
            Flux<AgentRuntimeEvent> source,
            Duration maxDuration,
            Consumer<AgentRuntimeEvent> observer,
            BiConsumer<AgentRuntimeEvent, RuntimeException> observerFailure) {
        return start(request, source, maxDuration, observer, observerFailure, () -> {
        });
    }

    /**
     * 为应用协调器提供最后一次观测完成后的本地清理钩子。
     *
     * @param request         当前执行请求。
     * @param source          借用的执行源。
     * @param maxDuration     总执行时间预算。
     * @param observer        串行观测回调。
     * @param observerFailure 已取得失败归属的收敛回调。
     * @param settled         最后一次观测完成后的本地清理，不应执行阻塞 I/O。
     * @return 本地观察流。
     */
    public Flux<AgentRuntimeEvent> start(
            AgentTurnRequest request,
            Flux<AgentRuntimeEvent> source,
            Duration maxDuration,
            Consumer<AgentRuntimeEvent> observer,
            BiConsumer<AgentRuntimeEvent, RuntimeException> observerFailure,
            Runnable settled) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxDuration.isZero() || maxDuration.isNegative())
            throw new IllegalArgumentException("maxDuration must be positive");
        SessionKey key = new SessionKey(request.getOwnerKey(), request.getSessionId());
        ManagedTurn created =
                new ManagedTurn(
                        key,
                        request,
                        replayEvents,
                        observer,
                        Objects.requireNonNull(observerFailure, "observerFailure"),
                        Objects.requireNonNull(settled, "settled"));
        synchronized (lifecycle) {
            if (closed) throw new ClosedException();
            turns.compute(
                    key,
                    (ignored, current) -> {
                        if (current != null && !current.released.get())
                            throw new TurnAlreadyRunningException();
                        return created;
                    });
        }
        try {
            // 订阅前先绑定，使并发关闭或取消也能取消后续注册的订阅。
            BaseSubscriber<AgentRuntimeEvent> subscriber =
                    new BaseSubscriber<>() {
                        /** 每次只请求一个事件，下一次请求在上一事件持久化完成后发出。 */
                        @Override
                        protected void hookOnSubscribe(Subscription subscription) {
                            request(1);
                        }

                        /** 将生产线程与串行持久化线程分开，保持有界在途事件数。 */
                        @Override
                        protected void hookOnNext(AgentRuntimeEvent event) {
                            created.enqueue(
                                    () -> {
                                        created.emit(event);
                                        if (created.ending.get() == null) request(1);
                                    });
                        }

                        /** 源错误与已接收的最后一个事件按同一通道顺序收敛。 */
                        @Override
                        protected void hookOnError(Throwable error) {
                            created.enqueue(() -> created.fail(error));
                        }

                        /** 源完成不抢先移除仍在持久化的执行。 */
                        @Override
                        protected void hookOnComplete() {
                            created.enqueue(created::complete);
                        }
                    };
            created.bind(subscriber);
            created.bindDeadline(
                    Mono.delay(maxDuration)
                            .publishOn(controls)
                            .subscribe(ignored -> timeout(key, created)));
            source.subscribeOn(Schedulers.boundedElastic()).subscribe(subscriber);
        } catch (RuntimeException error) {
            created.shutdown();
            throw error;
        }
        created.awaitRegistration();
        return created.events();
    }

    /**
     * 取得已有执行的观察流；核对期望执行标识，避免旧页面订阅到后续执行。
     *
     * @param ownerKey       宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId      会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param expectedTurnId 调用方期望操作的执行标识，用于防止旧页面误操作后续执行。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<AgentRuntimeEvent> subscribe(
            String ownerKey, String sessionId, String expectedTurnId) {
        if (closed) return Flux.error(new ClosedException());
        ManagedTurn current = turns.get(new SessionKey(ownerKey, sessionId));
        if (current == null) {
            return Flux.error(new TurnNotAvailableException());
        }
        if (!current.turnId.equals(expectedTurnId)) {
            return Flux.error(new TurnChangedException());
        }
        return current.events();
    }

    /**
     * 判断是否存在活跃执行。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean hasActiveTurn(String ownerKey, String sessionId) {
        ManagedTurn current = turns.get(new SessionKey(ownerKey, sessionId));
        return current != null && !current.released.get();
    }

    /**
     * 请求取消当前执行并返回实际取消状态，不把请求已提交等同于执行已经停止。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param ownerKey       宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId      会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param expectedTurnId 调用方期望操作的执行标识，用于防止旧页面误操作后续执行。
     * @return 本次操作返回的取消结果结果。
     * @throws ClosedException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public CancelResult cancel(String ownerKey, String sessionId, String expectedTurnId) {
        if (closed) throw new ClosedException();
        ManagedTurn current = turns.get(new SessionKey(ownerKey, sessionId));
        if (current == null) {
            Optional<SessionExecutionState> persisted =
                    runtime.sessionExecution(ownerKey, sessionId);
            if (persisted.isEmpty()) {
                return CancelResult.NOT_FOUND;
            }
            if (!persisted.get().getTurnId().equals(expectedTurnId)) {
                return CancelResult.TURN_CHANGED;
            }
            return persisted.get().getStatus().isTerminal()
                    ? CancelResult.ALREADY_TERMINAL
                    : CancelResult.NOT_OWNED;
        }
        if (!current.turnId.equals(expectedTurnId)) {
            return CancelResult.TURN_CHANGED;
        }
        if (!current.ending.compareAndSet(null, Ending.CONTROL))
            return CancelResult.ALREADY_TERMINAL;
        current.stop(
                () -> runtime.interruptCurrentTurn(ownerKey, sessionId, expectedTurnId),
                AgentRuntimeEvent.builder()
                        .type(AgentRuntimeEvent.Type.TURN_CANCELLED)
                        .turnId(expectedTurnId)
                        .sessionId(sessionId)
                        .id(expectedTurnId)
                        .title("已取消")
                        .text("本轮执行已取消。")
                        .status("cancelled")
                        .build());

        return CancelResult.CANCELLED;
    }

    /**
     * 按指定执行标识请求超时收敛，防止结束同一会话中的后续执行。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param key     当前对象的查找或写入键。
     * @param current 当前执行执行管理器持有的当前对象，供相应处理步骤使用。
     */
    private void timeout(SessionKey key, ManagedTurn current) {
        if (!current.ending.compareAndSet(null, Ending.CONTROL)) return;
        current.stop(
                () ->
                        runtime.timeoutCurrentTurn(
                                key.getOwnerKey(), key.getSessionId(), current.turnId),
                AgentRuntimeEvent.builder()
                        .type(AgentRuntimeEvent.Type.TURN_TIMED_OUT)
                        .turnId(current.turnId)
                        .sessionId(key.getSessionId())
                        .id(current.turnId)
                        .title("执行超时")
                        .text("本轮执行已达到最长运行时间，执行已停止。")
                        .status("timed_out")
                        .details(Map.of("errorCode", "HOST_TIMEOUT"))
                        .build());
    }

    /**
     * 关闭执行管理器并释放其持有的活动执行与调度资源。
     * 共享状态的关键更新在互斥区内完成。
     */
    @Override
    public void close() {
        List<ManagedTurn> active;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            active = List.copyOf(turns.values());
            turns.clear();
        }
        active.forEach(ManagedTurn::shutdown);
        controls.dispose();
    }

    /**
     * 关闭异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。
     */
    public static final class ClosedException extends IllegalStateException {
        /**
         * 创建关闭异常，初始化该组件所需的状态、配置或依赖。
         */
        public ClosedException() {
            super("Turn execution manager is closed");
        }
    }

    /**
     * 取消操作的结果，区分已提交控制请求与执行已经确认停止。
     */
    public enum CancelResult {
        /**
         * 执行或交互已取消，不再继续原处理。
         */
        CANCELLED,
        /**
         * 目标执行已经结束，不再重复取消。
         */
        ALREADY_TERMINAL,
        /**
         * 原会话的当前执行已经变化，旧请求不能作用于后续执行。
         */
        TURN_CHANGED,
        /**
         * 本实例没有持有目标执行，需通过对应的持有方处理。
         */
        NOT_OWNED,
        /**
         * 在当前访问范围内没有找到所请求对象。
         */
        NOT_FOUND
    }

    /**
     * 执行Already运行中异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。
     */
    public static final class TurnAlreadyRunningException extends IllegalStateException {
        /**
         * 创建执行Already运行中异常，初始化该组件所需的状态、配置或依赖。
         */
        TurnAlreadyRunningException() {
            super("session already has a running turn");
        }
    }

    /**
     * 执行Not可用异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。
     */
    public static final class TurnNotAvailableException extends IllegalStateException {
        /**
         * 创建执行Not可用异常，初始化该组件所需的状态、配置或依赖。
         */
        TurnNotAvailableException() {
            super("turn event stream is not available on this instance");
        }
    }

    /**
     * 执行已改变异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。
     */
    public static final class TurnChangedException extends IllegalStateException {
        /**
         * 创建执行已改变异常，初始化该组件所需的状态、配置或依赖。
         */
        TurnChangedException() {
            super("session current turn has changed");
        }
    }

    /**
     * 执行管理器定位隔离会话的键，组合归属与会话标识。
     */
    @Value
    private static class SessionKey {
        /**
         * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
         */
        String ownerKey;

        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        String sessionId;
    }

    /**
     * 终态归属；首次原子转换决定谁收敛执行，后续事件不能覆盖它。
     */
    private enum Ending {
        /**
         * 由 Runtime 的终态事件收敛。
         */
        SOURCE,
        /**
         * 由宿主取消或总时限收敛。
         */
        CONTROL,
        /**
         * 由源错误、写入错误或缺少终态收敛。
         */
        FAILURE,
        /**
         * 由宿主关闭收敛。
         */
        SHUTDOWN
    }

    /**
     * 执行句柄；原子状态负责终态竞争，邮箱负责观测及输出顺序。
     */
    private final class ManagedTurn {
        /**
         * 所属隔离会话；仅以本句柄移除，避免删除同会话的新执行。
         */
        private final SessionKey key;

        /**
         * 当前执行请求。
         */
        private final AgentTurnRequest request;

        /**
         * 当前执行标识。
         */
        private final String turnId;

        /**
         * 本地有界回放输出。
         */
        private final Sinks.Many<AgentRuntimeEvent> sink;

        /**
         * 串行观测回调，由事件工作线程执行。
         */
        private final Consumer<AgentRuntimeEvent> observer;

        /**
         * 写入错误取得终态后的持久化收敛回调。
         */
        private final BiConsumer<AgentRuntimeEvent, RuntimeException> observerFailure;

        /**
         * 串行事件排空后的本地清理钩子，避免源关闭抢先清空仍在持久化的工具事实。
         */
        private final Runnable settled;

        /**
         * 借用源的订阅，本句柄负责取消。
         */
        private final AtomicReference<Disposable> upstream = new AtomicReference<>();

        /**
         * 总时限调度订阅。
         */
        private final AtomicReference<Disposable> deadline = new AtomicReference<>();

        /**
         * 终态归属，一次执行最多成功转换一次。
         */
        private final AtomicReference<Ending> ending = new AtomicReference<>();

        /**
         * 句柄已释放；在持久化排空前保留会话占用。
         */
        private final AtomicBoolean released = new AtomicBoolean();

        /**
         * 当前观察流已发送结果或异常通知，仅在邮箱线程中读写。
         */
        private boolean resultSeen;

        /**
         * 已发送需要等待用户的交互事件，仅在邮箱线程中读写。
         */
        private boolean waitingSeen;

        /**
         * 已请求释放源，包括在注册之前接受的取消。
         */
        private final AtomicBoolean cancelRequested = new AtomicBoolean();

        /**
         * 不等待持久化即可确认开始、停止或失败。
         */
        private final CountDownLatch registered = new CountDownLatch(1);

        /**
         * 串行邮箱；源每次请求一个事件，控制和源收尾至多再加入少量命令。
         */
        private final ConcurrentLinkedQueue<Runnable> mailbox = new ConcurrentLinkedQueue<>();

        /**
         * 原子保护排空任务的唯一性，不在锁内执行观测或订阅释放。
         */
        private final AtomicBoolean draining = new AtomicBoolean();

        /**
         * 创建借用 Runtime、拥有源订阅和本地观察通道的执行句柄。
         */
        private ManagedTurn(
                SessionKey key,
                AgentTurnRequest request,
                int replayEvents,
                Consumer<AgentRuntimeEvent> observer,
                BiConsumer<AgentRuntimeEvent, RuntimeException> observerFailure,
                Runnable settled) {
            this.key = key;
            this.request = request;
            this.turnId = request.getTurnId();
            this.sink = Sinks.many().replay().limit(replayEvents);
            this.observer = observer == null ? ignored -> {
            } : observer;
            this.observerFailure = observerFailure;
            this.settled = settled;
        }

        /**
         * 将命令排入唯一的串行通道；共享工作线程被占用不影响独立控制池。
         */
        private void enqueue(Runnable action) {
            if (released.get()) return;
            mailbox.add(action);
            if (draining.compareAndSet(false, true)) {
                Schedulers.boundedElastic().schedule(this::drain);
            }
        }

        /**
         * 回调执行不占状态锁；空邮箱与并发入队通过再次检查防止漏唤醒。
         */
        private void drain() {
            do {
                Runnable action;
                while ((action = mailbox.poll()) != null) {
                    if (released.get()) continue;
                    action.run();
                }
                draining.set(false);
            } while (!mailbox.isEmpty() && draining.compareAndSet(false, true));
        }

        /**
         * 注册源订阅；停止先于注册发生时也不允许重新启动生产者。
         */
        private void bind(Disposable value) {
            if (!upstream.compareAndSet(null, value)) {
                disposeSource(value);
            } else if (cancelRequested.get()) {
                cancelUpstream();
            }
        }

        /**
         * 注册总时限；执行已经取得终态时立即释放迟到的调度任务。
         */
        private void bindDeadline(Disposable value) {
            if (!deadline.compareAndSet(null, value) || ending.get() != null) value.dispose();
        }

        /**
         * 先取得停止归属并中断执行，观测与终态写入排在已经进行的写入之后。
         * Runtime 操作和订阅释放均不持有生命周期锁或持久化锁。
         */
        private void stop(Runnable interrupt, AgentRuntimeEvent event) {
            registered.countDown();
            cancelDeadline();
            Throwable failure = null;
            try {
                interrupt.run();
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                cancelUpstream();
            }
            Throwable controlFailure = failure;
            enqueue(
                    () -> {
                        if (ending.get() != Ending.CONTROL) return;
                        if (controlFailure == null) deliver(event);
                        else reportFailure("EXECUTION_ERROR", controlFailure, false);
                        release();
                    });
        }

        /**
         * 对开始的写入保持原顺序；停止后尚未开始的事件和迟到终态直接丢弃。
         * 在观测完成后才向本地流发布，保证持久化和客户端看到的事实顺序一致。
         */
        private void emit(AgentRuntimeEvent event) {
            if (ending.get() != null) return;
            boolean terminalEvent = terminalEvent(event.getType());
            if (terminalEvent && !ending.compareAndSet(null, Ending.SOURCE)) return;
            if (event.getType() == AgentRuntimeEvent.Type.TURN_STARTED) registered.countDown();
            if (terminalEvent) cancelDeadline();
            try {
                observer.accept(event);
            } catch (Throwable error) {
                RuntimeException callbackFailure = callbackFailure(error);
                if (ending.compareAndSet(null, Ending.FAILURE) || ending.get() == Ending.SOURCE) {
                    handleObserverFailure(event, callbackFailure);
                    release();
                }
                return;
            }
            if (ending.get() == Ending.SHUTDOWN) return;
            if (event.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED
                    || event.getType() == AgentRuntimeEvent.Type.ASK_USER_REQUIRED)
                waitingSeen = true;
            sink.tryEmitNext(event);
            if (terminalEvent) {
                resultSeen = true;
                release();
            }
        }

        /**
         * 控制终态也由同一通道观测和发布，避免与已经进行的写入并发。
         */
        private void deliver(AgentRuntimeEvent event) {
            try {
                observer.accept(event);
            } catch (Throwable error) {
                handleObserverFailure(event, callbackFailure(error));
                return;
            }
            resultSeen = true;
            sink.tryEmitNext(event);
        }

        /**
         * 仅取得失败归属的事件调用收敛器；停止期间的旧写入失败不能改成 FAILED。
         */
        private void handleObserverFailure(AgentRuntimeEvent event, RuntimeException error) {
            log.error(
                    "Turn event observer failed [turnId={}, type={}]",
                    turnId,
                    event.getType(),
                    error);
            try {
                observerFailure.accept(event, error);
            } catch (Throwable secondary) {
                log.warn(
                        "Unable to settle observer failure [turnId={}]",
                        turnId,
                        callbackFailure(secondary));
            }
            reportFailure(
                    "EVENT_PERSISTENCE_FAILED",
                    error,
                    event.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED);
        }

        /**
         * 保留 Reactor 的致命错误语义；普通回调 Error 也必须完成失败收敛。
         */
        private RuntimeException callbackFailure(Throwable error) {
            Exceptions.throwIfFatal(error);
            return Exceptions.propagate(error);
        }

        /**
         * 源错误只在没有其他操作取得终态时转为失败。
         */
        private void fail(Throwable error) {
            if (!ending.compareAndSet(null, Ending.FAILURE)) return;
            reportFailure("EXECUTION_ERROR", error, false);
            release();
        }

        /**
         * 源结束后等待用户可正常收尾；未发出任何终态或等待事件视为协议失败。
         */
        private void complete() {
            if (waitingSeen) {
                if (ending.compareAndSet(null, Ending.SOURCE)) release();
            } else if (ending.compareAndSet(null, Ending.FAILURE)) {
                reportFailure("MISSING_TERMINAL_RESULT", null, false);
                release();
            }
        }

        /**
         * 尽力写入一次失败事实；完成结果可能已经提交时只发结果不确定通知。
         */
        private void reportFailure(String code, Throwable cause, boolean resultMayBeCommitted) {
            if (resultSeen) return;
            if (!resultMayBeCommitted) {
                try {
                    runtime.failCurrentTurn(
                            request.getOwnerKey(), request.getSessionId(), turnId, code);
                } catch (RuntimeException error) {
                    log.warn(
                            "Unable to save fallback Turn state [turnId={}, code={}]",
                            turnId,
                            code,
                            error);
                }
            }
            AgentRuntimeEvent fallback =
                    AgentRuntimeEvent.builder()
                            .type(
                                    resultMayBeCommitted
                                            ? AgentRuntimeEvent.Type.EXECUTION_NOTICE
                                            : AgentRuntimeEvent.Type.TURN_FAILED)
                            .turnId(turnId)
                            .sessionId(request.getSessionId())
                            .id(turnId)
                            .title("执行异常")
                            .text(
                                    resultMayBeCommitted
                                            ? "结果保存或通知出现异常，请重新加载会话确认结果。"
                                            : "本轮执行出现异常，已停止。")
                            .status("error")
                            .details(Map.of("errorCode", code))
                            .build();
            try {
                observer.accept(fallback);
            } catch (Throwable error) {
                RuntimeException callbackFailure = callbackFailure(error);
                log.warn(
                        "Unable to persist fallback notification [turnId={}, code={}]",
                        turnId,
                        code,
                        callbackFailure);
            }
            if (cause != null)
                log.warn("Turn fallback notification [turnId={}, code={}]", turnId, code, cause);
            resultSeen = true;
            registered.countDown();
            sink.tryEmitNext(fallback);
        }

        /**
         * 先停止生产者，不在宿主关闭线程等待阻塞的观测回调。
         */
        private void shutdown() {
            ending.set(Ending.SHUTDOWN);
            registered.countDown();
            cancelDeadline();
            cancelUpstream();
            enqueue(this::release);
        }

        /**
         * 完成写入后再释放会话占用，最后通知订阅者流结束。
         */
        private void release() {
            // 只由唯一邮箱线程调用；资源清理完成后才允许同会话启动新执行。
            if (released.get()) return;
            cancelDeadline();
            cancelUpstream();
            registered.countDown();
            mailbox.clear();
            try {
                settled.run();
            } catch (RuntimeException error) {
                log.warn("Turn local cleanup failed [turnId={}]", turnId, error);
            }
            turns.remove(key, this);
            released.set(true);
            sink.tryEmitComplete();
        }

        /**
         * 本句柄的只读观察通道。
         */
        private Flux<AgentRuntimeEvent> events() {
            return sink.asFlux();
        }

        /**
         * 释放订阅可能调用外部 finally 钩子，因此始终在状态锁外执行。
         */
        private void cancelUpstream() {
            cancelRequested.set(true);
            Disposable value = upstream.getAndSet(null);
            if (value != null) disposeSource(value);
        }

        /**
         * 源的用户 finally 钩子即使失败，也不能阻止终态排队与会话释放。
         */
        private void disposeSource(Disposable value) {
            try {
                value.dispose();
            } catch (RuntimeException error) {
                log.warn("Turn source cleanup failed [turnId={}]", turnId, error);
            }
        }

        /**
         * 释放本执行的截止调度。
         */
        private void cancelDeadline() {
            Disposable value = deadline.get();
            if (value != null && !value.isDisposed()) value.dispose();
        }

        /**
         * 只等待执行开始或停止登记，不等待观测和持久化完成。
         */
        private void awaitRegistration() {
            try {
                registered.await(START_REGISTRATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Runtime 协议中真正结束执行的事件类型。
         */
        private static boolean terminalEvent(AgentRuntimeEvent.Type type) {
            return type == AgentRuntimeEvent.Type.TURN_COMPLETED
                    || type == AgentRuntimeEvent.Type.TURN_FAILED
                    || type == AgentRuntimeEvent.Type.TURN_CANCELLED
                    || type == AgentRuntimeEvent.Type.TURN_TIMED_OUT;
        }
    }
}
