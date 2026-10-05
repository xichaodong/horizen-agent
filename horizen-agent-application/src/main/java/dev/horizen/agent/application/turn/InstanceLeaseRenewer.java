package dev.horizen.agent.application.turn;

import static dev.horizen.agent.common.error.Exceptions.rootCause;

import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TurnStatus;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 每实例一个调度器，对本实例实际执行的 Turn 做有界并发批量续租。 */
public final class InstanceLeaseRenewer implements AutoCloseable {
    /** 当前组件的诊断日志器。 */
    private static final Logger log = LoggerFactory.getLogger(InstanceLeaseRenewer.class);

    /** 负责turns对应持久化访问的仓储依赖；调用方通过端口隔离具体存储实现。 */
    private final SessionTurnStore turns;

    /** 当前服务实例标识，用于区分分布式执行与资源统计。 */
    private final String instanceId;

    /** 执行实例租约的有效时长。 */
    private final Duration leaseTtl;

    /** 心跳的时间配置，供等待、调度或失效判断使用。 */
    private final Duration heartbeat;

    /** 宿主绑定的配置对象，供组件组装与策略校验使用。 */
    private final LeaseRenewalPolicy properties;

    /** tracked的索引映射，供按键查找或归并当前组件的数据。 */
    private final Map<String, LeaseTarget> tracked = new ConcurrentHashMap<>();

    /** rounds的原子状态，供并发更新与统计读取使用。 */
    private final AtomicLong rounds = new AtomicLong();

    /** 已成功续期的执行累计数量。 */
    private final AtomicLong renewedTurns = new AtomicLong();

    /** failures的原子状态，供并发更新与统计读取使用。 */
    private final AtomicLong failures = new AtomicLong();

    /** 最近处理批次包含的执行数量。 */
    private final AtomicInteger lastBatchSize = new AtomicInteger();

    /** 最近一轮续期处理的执行数量。 */
    private final AtomicInteger lastRoundTurns = new AtomicInteger();

    /** 最近一轮续期划分的批次数量。 */
    private final AtomicInteger lastRoundBatches = new AtomicInteger();

    /** 当前仍在执行续期操作的批次数量。 */
    private final AtomicInteger activeBatches = new AtomicInteger();

    /** 观测到的同时续期批次数量峰值。 */
    private final AtomicInteger maxConcurrentBatches = new AtomicInteger();

    /** 最大轮次纳秒的原子状态，供并发更新与统计读取使用。 */
    private final AtomicLong maxRoundNanos = new AtomicLong();

    /** 周期任务的调度器，用于心跳、轮询或续租等定时工作。 */
    private volatile Disposable scheduler;

    /** 组件是否已关闭，用于避免重复释放或继续接收新工作。 */
    private boolean closed;

    /**
     * 创建实例租约续期器，初始化该组件所需的状态、配置或依赖。
     *
     * @param turns 提供执行集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param instanceId 当前服务实例标识，用于区分分布式执行与资源统计。
     * @param leaseTtl 执行实例租约的有效时长。
     * @param heartbeat 心跳的时间配置，供等待、调度或失效判断使用。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     */
    public InstanceLeaseRenewer(
            SessionTurnStore turns,
            String instanceId,
            Duration leaseTtl,
            Duration heartbeat,
            LeaseRenewalPolicy properties) {
        this.turns = Objects.requireNonNull(turns, "turns");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** 注册需要由本实例续期的执行，使网络准备和长时间执行期间仍能保持租约。 */
    public synchronized void start() {
        if (closed || scheduler != null) return;
        scheduler =
                Flux.defer(() -> Mono.delay(heartbeat).then(Mono.defer(this::runOnce)))
                        .repeat()
                        .subscribe(
                                ignored -> {},
                                error ->
                                        log.warn(
                                                "Lease renewer stopped [error={}]",
                                                rootCause(error).getClass().getSimpleName()));
    }

    /**
     * 完成当前操作的track步骤，按实现更新相应状态或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    public synchronized void track(String ownerKey, String turnId) {
        if (closed) return;
        tracked.put(key(ownerKey, turnId), new LeaseTarget(ownerKey, turnId));
    }

    /**
     * 完成当前操作的untrack步骤，按实现更新相应状态或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    public void untrack(String ownerKey, String turnId) {
        tracked.remove(key(ownerKey, turnId));
    }

    /**
     * 按当前跟踪集合分批续期租约，并维护续期统计。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    Mono<Void> runOnce() {
        long startedAt = System.nanoTime();
        List<LeaseTarget> targets = List.copyOf(tracked.values());
        rounds.incrementAndGet();
        lastRoundTurns.set(targets.size());
        if (targets.isEmpty()) {
            lastRoundBatches.set(0);
            lastBatchSize.set(0);
            return Mono.empty();
        }
        List<List<LeaseTarget>> batches = partition(targets, properties.getBatchSize());
        lastRoundBatches.set(batches.size());
        lastBatchSize.set(batches.stream().mapToInt(List::size).max().orElse(0));
        return Flux.fromIterable(batches)
                .flatMap(this::renewBatch, properties.getConcurrency())
                .then()
                .doFinally(
                        ignored ->
                                maxRoundNanos.accumulateAndGet(
                                        System.nanoTime() - startedAt, Math::max));
    }

    /**
     * 仅对本批次仍可续期的执行更新租约，在有限尝试内处理短暂失败。
     *
     * @param targets targets的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private Mono<Void> renewBatch(List<LeaseTarget> targets) {
        return Mono.defer(
                () -> {
                    int active = activeBatches.incrementAndGet();
                    maxConcurrentBatches.accumulateAndGet(active, Math::max);
                    AtomicBoolean released = new AtomicBoolean();
                    Mono<Integer> write =
                            Mono.fromCallable(
                                            () -> {
                                                Map<String, List<String>> byOwner =
                                                        new LinkedHashMap<>();
                                                for (LeaseTarget target : targets) {
                                                    byOwner.computeIfAbsent(
                                                                    target.getOwnerKey(),
                                                                    ignored -> new ArrayList<>())
                                                            .add(target.getTurnId());
                                                }
                                                Instant now = Instant.now();
                                                return turns.renewLeases(
                                                        byOwner,
                                                        instanceId,
                                                        now.plus(leaseTtl),
                                                        now);
                                            })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .retryWhen(
                                            Retry.fixedDelay(
                                                    properties.getRetries(),
                                                    properties.getRetryDelay()));
                    return write.doOnNext(
                                    renewed -> {
                                        renewedTurns.addAndGet(renewed);
                                        if (renewed < targets.size()) prune(targets, Instant.now());
                                    })
                            .onErrorResume(
                                    error -> {
                                        failures.incrementAndGet();
                                        log.warn(
                                                "Turn lease batch renewal failed after retry "
                                                        + "[batch={}, error={}]",
                                                targets.size(),
                                                rootCause(error).getClass().getSimpleName());
                                        return Mono.empty();
                                    })
                            .then()
                            .doOnSuccess(ignored -> release(released))
                            .doOnError(ignored -> release(released))
                            .doOnCancel(() -> release(released));
                });
    }

    /**
     * 移除已经结束或不应继续续期的执行跟踪项。
     *
     * @param targets targets的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param now 用于本次更新或过期判断的当前时间。
     */
    private void prune(List<LeaseTarget> targets, Instant now) {
        for (LeaseTarget target : targets) {
            try {
                AgentTurn current =
                        turns.findTurn(target.getOwnerKey(), target.getTurnId()).orElse(null);
                if (current == null
                        || current.getStatus() != TurnStatus.RUNNING
                        || !instanceId.equals(current.getExecutorId())
                        || current.getLeaseExpiresAt() == null
                        || !current.getLeaseExpiresAt().isAfter(now)
                        || !current.getDeadlineAt().isAfter(now)) {
                    tracked.remove(key(target.getOwnerKey(), target.getTurnId()), target);
                }
            } catch (RuntimeException error) {
                log.warn(
                        "Lease target pruning failed [turnId={}, error={}]",
                        target.getTurnId(),
                        rootCause(error).getClass().getSimpleName());
            }
        }
    }

    /**
     * 读取本实例续期任务的统计快照。
     *
     * @return 本次操作返回的状态结果。
     */
    public Status status() {
        return new Status(
                tracked.size(),
                rounds.get(),
                renewedTurns.get(),
                failures.get(),
                lastRoundTurns.get(),
                lastRoundBatches.get(),
                lastBatchSize.get(),
                activeBatches.get(),
                maxConcurrentBatches.get(),
                Duration.ofNanos(maxRoundNanos.get()).toMillis());
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (scheduler != null) scheduler.dispose();
        scheduler = null;
        tracked.clear();
    }

    /**
     * 释放实例租约续期器。
     * 并发状态更新包含比较交换操作。
     *
     * @param released released的原子状态，供并发更新与统计读取使用。
     */
    private void release(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) activeBatches.decrementAndGet();
    }

    /**
     * 将待处理执行划分为受批次上限约束的小批次。
     *
     * @param values 本次批量处理的值集合。
     * @param size 当前内容或集合的大小，计量方式由所属资源协议定义。
     * @return 本次处理得到的结果集合。
     */
    private static List<List<LeaseTarget>> partition(List<LeaseTarget> values, int size) {
        List<List<LeaseTarget>> batches = new ArrayList<>();
        for (int start = 0; start < values.size(); start += size) {
            batches.add(List.copyOf(values.subList(start, Math.min(start + size, values.size()))));
        }
        return batches;
    }

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理生成或读取的文本。
     */
    private static String key(String ownerKey, String turnId) {
        return ownerKey + '\u0000' + turnId;
    }

    /** 当前需要续期的执行定位信息。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class LeaseTarget {
        /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
        @Getter(AccessLevel.PRIVATE)
        private final String ownerKey;

        /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
        @Getter(AccessLevel.PRIVATE)
        private final String turnId;
    }

    /** 本实例租约续期任务的运行统计，供资源状态接口观察。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Status {
        /** 当前由本实例跟踪并需要续期的执行数量。 */
        private int trackedTurns;

        /** 本实例已经运行的租约续期轮次数。 */
        private long renewalRounds;

        /** 已成功续期的执行累计数量。 */
        private long renewedTurns;

        /** 租约续期过程中累计记录的失败次数。 */
        private long renewalFailures;

        /** 最近一轮续期处理的执行数量。 */
        private int lastRoundTurns;

        /** 最近一轮续期划分的批次数量。 */
        private int lastRoundBatches;

        /** 最近处理批次包含的执行数量。 */
        private int lastBatchSize;

        /** 当前仍在执行续期操作的批次数量。 */
        private int activeBatches;

        /** 观测到的同时续期批次数量峰值。 */
        private int maxConcurrentBatches;

        /** 最大续期耗时，单位为毫秒。 */
        private long maxRenewalDurationMs;
    }
}
