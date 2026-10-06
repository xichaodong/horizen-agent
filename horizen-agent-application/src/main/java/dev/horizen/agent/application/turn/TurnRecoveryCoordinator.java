package dev.horizen.agent.application.turn;

import static dev.horizen.agent.common.error.Exceptions.rootCause;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.ApplicationError.Code;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.MessageRole;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 处理跨实例取消、租约续期和失去执行宿主的 Turn 恢复。
 */
public final class TurnRecoveryCoordinator implements AutoCloseable {
    /**
     * 当前组件的诊断日志器。
     */
    private static final Logger log = LoggerFactory.getLogger(TurnRecoveryCoordinator.class);

    /**
     * 跨实例执行控制通道，将取消请求送到原执行持有方。
     */
    private final TurnControlChannel controls;

    /**
     * 当前对象使用的处理策略，决定校验、权限或执行边界。
     */
    private final TurnRecoveryPolicy policy;

    /**
     * 会话对象或会话索引，按相应的归属键定位数据。
     */
    private final SessionTurnStore sessions;

    /**
     * 执行 Agent 模型与工具循环的运行时接口。
     */
    private final AgentRuntime runtime;

    /**
     * 当前实例持有的执行句柄管理器。
     */
    private final TurnExecutionManager executions;

    /**
     * 当前实例的执行租约续期管理器。
     */
    private final InstanceLeaseRenewer leases;

    /**
     * 把运行事件收敛为正式状态、交互记录与回放增量的持久化协调器。
     */
    private final TurnEventPersistence eventPersistence;

    /**
     * 跨实例控制信号的轮询订阅句柄，关闭时需要停止。
     */
    private Disposable controlPoller;

    /**
     * 租约失联与执行过期检查的周期任务句柄。
     */
    private Disposable leaseReconciler;

    /**
     * 开始的状态标记，用于选择当前组件的处理路径。
     */
    private boolean started;

    /**
     * 组件是否已关闭，用于避免重复释放或继续接收新工作。
     */
    private boolean closed;

    /**
     * 创建执行恢复协调器，初始化该组件所需的状态、配置或依赖。
     *
     * @param controls         当前执行恢复协调器持有的控制集合对象，供相应处理步骤使用。
     * @param policy           当前对象使用的处理策略，决定校验、权限或执行边界。
     * @param sessions         会话对象或会话索引，按相应的归属键定位数据。
     * @param runtime          执行 Agent 模型与工具循环的运行时接口。
     * @param executions       当前执行恢复协调器持有的执行集合对象，供相应处理步骤使用。
     * @param leases           当前执行恢复协调器持有的租约集合对象，供相应处理步骤使用。
     * @param eventPersistence 当前执行恢复协调器持有的事件持久化对象，供相应处理步骤使用。
     */
    public TurnRecoveryCoordinator(
            TurnControlChannel controls,
            TurnRecoveryPolicy policy,
            SessionTurnStore sessions,
            AgentRuntime runtime,
            TurnExecutionManager executions,
            InstanceLeaseRenewer leases,
            TurnEventPersistence eventPersistence) {
        this.controls = Objects.requireNonNull(controls, "controls");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.leases = leases;
        this.eventPersistence = Objects.requireNonNull(eventPersistence, "eventPersistence");
    }

    /**
     * 启动分布式执行控制轮询与租约失联检查。
     */
    public synchronized void start() {
        if (started || closed) return;
        started = true;
        if (leases != null) leases.start();
        controlPoller = startControlPoller();
        leaseReconciler = startLeaseReconciler();
    }

    /**
     * 核对当前执行与实例归属，选择本地取消或跨实例取消控制。
     *
     * @param identity  可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void cancel(ExecutionIdentity identity, String sessionId, String turnId) {
        AgentTurn current =
                sessions.findTurn(identity.getOwnerKey(), turnId)
                        .orElseThrow(
                                () -> new ApplicationError(Code.NOT_FOUND, "Session 没有可取消的 Turn"));
        if (!current.getSessionId().equals(sessionId)) {
            throw new ApplicationError(Code.CONFLICT, "当前 Turn 已变化，未执行取消");
        }
        if (current.getStatus().isTerminal()) return;
        TransitionTurnResult requested =
                sessions.transitionTurn(
                        new TransitionTurnCommand(
                                identity.getOwnerKey(),
                                sessionId,
                                turnId,
                                TurnStatus.CANCELLING,
                                null,
                                null,
                                Instant.now(),
                                null,
                                null,
                                null));
        if (requested.getOutcome() == TransitionTurnResult.Outcome.TURN_CHANGED
                || requested.getOutcome() == TransitionTurnResult.Outcome.STATUS_CHANGED) {
            throw new ApplicationError(Code.CONFLICT, "当前 Turn 状态已变化，未执行取消");
        }
        AgentTurn cancelling = requested.getTurn() == null ? current : requested.getTurn();
        if (current.getStatus() == TurnStatus.WAITING_APPROVAL
                || cancelling.getExecutorId() == null) {
            runtime.interruptCurrentTurn(identity.getOwnerKey(), sessionId, turnId);
            finishCancellation(identity.getOwnerKey(), sessionId, turnId);
            return;
        }
        if (policy.getInstanceId().equals(cancelling.getExecutorId())) {
            TurnExecutionManager.CancelResult result =
                    executions.cancel(identity.getOwnerKey(), sessionId, turnId);
            if (result == TurnExecutionManager.CancelResult.ALREADY_TERMINAL) {
                finishCancellation(identity.getOwnerKey(), sessionId, turnId);
            }
        } else {
            sendCancelControl(cancelling);
        }
        return;
    }

    /**
     * 从正式历史构造已结束执行的回放结果，不依赖仍然存活的运行订阅。
     *
     * @param turn 当前执行恢复协调器持有的执行对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Flux<AgentRuntimeEvent> replayTerminal(AgentTurn turn) {
        if (!turn.getStatus().isTerminal()) {
            throw new IllegalArgumentException("Turn is not terminal");
        }
        String text =
                turn.getStatus() == TurnStatus.COMPLETED
                        ? sessions
                        .listFinalMessages(turn.getOwnerKey(), turn.getSessionId())
                        .stream()
                        .filter(message -> message.getTurnId().equals(turn.getTurnId()))
                        .filter(message -> message.getRole() == MessageRole.ASSISTANT)
                        .map(ConversationMessage::getContent)
                        .findFirst()
                        .orElse("")
                        : null;
        AgentRuntimeEvent.Type type =
                switch (turn.getStatus()) {
                    case COMPLETED -> AgentRuntimeEvent.Type.TURN_COMPLETED;
                    case FAILED -> AgentRuntimeEvent.Type.TURN_FAILED;
                    case CANCELLED -> AgentRuntimeEvent.Type.TURN_CANCELLED;
                    case TIMED_OUT -> AgentRuntimeEvent.Type.TURN_TIMED_OUT;
                    default -> throw new IllegalStateException("非终态 Turn 不能构造幂等结果");
                };
        return Flux.just(
                AgentRuntimeEvent.builder()
                        .type(type)
                        .turnId(turn.getTurnId())
                        .sessionId(turn.getSessionId())
                        .id(turn.getTurnId())
                        .title("幂等返回")
                        .text(text)
                        .status(turn.getStatus().name().toLowerCase(Locale.ROOT))
                        .details(
                                turn.getFailureCode() == null
                                        ? null
                                        : Map.of("errorCode", turn.getFailureCode()))
                        .build());
    }

    /**
     * 计算或取得本方法声明的结果，供当前TurnRecoveryCoordinator处理步骤使用。
     *
     * @return 本次操作返回的状态结果。
     */
    public InstanceLeaseRenewer.Status status() {
        return leases == null ? new InstanceLeaseRenewer.Status() : leases.status();
    }

    /**
     * 周期读取发送给本实例的执行控制信号。
     *
     * @return 本次操作返回的资源句柄结果。
     */
    private Disposable startControlPoller() {
        return Mono.defer(() -> controls.drain(policy.getInstanceId(), 100))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(
                        error -> {
                            log.warn(
                                    "Control queue drain failed [error={}]",
                                    rootCause(error).getClass().getSimpleName());
                            return Mono.just(List.of());
                        })
                .flatMapMany(Flux::fromIterable)
                .concatMap(
                        signal ->
                                Mono.fromRunnable(() -> handleControl(signal))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(50)))
                                        .onErrorResume(
                                                error -> {
                                                    log.warn(
                                                            "Cancel control failed after retry [turnId={}, error={}]",
                                                            signal.getTurnId(),
                                                            rootCause(error)
                                                                    .getClass()
                                                                    .getSimpleName());
                                                    return Mono.empty();
                                                }))
                .then()
                .repeatWhen(rounds -> rounds.delayElements(policy.getControlPollInterval()))
                .subscribe(ignored -> {
                }, error -> log.warn("Control poller stopped", error));
    }

    /**
     * 周期检查到期租约与执行截止时间，收敛失联或过期的执行。
     *
     * @return 本次操作返回的资源句柄结果。
     */
    private Disposable startLeaseReconciler() {
        return Mono.fromRunnable(this::reconcileExpiredTurns)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(
                        error -> {
                            log.warn(
                                    "Lease reconciliation iteration failed [error={}]",
                                    rootCause(error).getClass().getSimpleName());
                            return Mono.empty();
                        })
                .repeatWhen(rounds -> rounds.delayElements(policy.getLeaseHeartbeat()))
                .delaySubscription(policy.getLeaseHeartbeat())
                .subscribe();
    }

    /**
     * 读取需要收敛的执行事实，并按最新状态应用失联或超时规则。
     */
    private void reconcileExpiredTurns() {
        Instant now = Instant.now();
        sessions.findOverdueTurns(now, 100)
                .forEach(
                        turn ->
                                recoverSafely(
                                        turn, TurnStatus.TIMED_OUT, "TURN_DEADLINE_EXCEEDED", now));
        sessions.findExpiredLeases(now, 100)
                .forEach(turn -> recoverSafely(turn, TurnStatus.FAILED, "EXECUTOR_LOST", now));
    }

    /**
     * 隔离单条执行恢复失败，使它不终止后续协调任务。
     *
     * @param turn        当前执行恢复协调器持有的执行对象，供相应处理步骤使用。
     * @param target      本次转换、状态更新或内容写入的目标。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @param now         用于本次更新或过期判断的当前时间。
     */
    private void recoverSafely(AgentTurn turn, TurnStatus target, String failureCode, Instant now) {
        try {
            recover(turn, target, failureCode, now);
        } catch (RuntimeException error) {
            log.warn(
                    "Turn recovery failed [turnId={}, error={}]",
                    turn.getTurnId(),
                    rootCause(error).getClass().getSimpleName());
        }
    }

    /**
     * 根据当前执行事实判断是否仍可由原实例继续持有或需要结束执行。
     *
     * @param turn        当前执行恢复协调器持有的执行对象，供相应处理步骤使用。
     * @param target      本次转换、状态更新或内容写入的目标。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @param now         用于本次更新或过期判断的当前时间。
     */
    void recover(AgentTurn turn, TurnStatus target, String failureCode, Instant now) {
        TransitionTurnResult result =
                sessions.transitionTurn(
                        new TransitionTurnCommand(
                                turn.getOwnerKey(),
                                turn.getSessionId(),
                                turn.getTurnId(),
                                target,
                                null,
                                null,
                                now,
                                failureCode,
                                null,
                                null)
                                .expectVersion(turn.getVersion()));
        if (result.getOutcome() != TransitionTurnResult.Outcome.UPDATED) return;
        try {
            if (target == TurnStatus.TIMED_OUT)
                runtime.timeoutCurrentTurn(
                        turn.getOwnerKey(), turn.getSessionId(), turn.getTurnId());
            else
                runtime.failCurrentTurn(
                        turn.getOwnerKey(), turn.getSessionId(), turn.getTurnId(), failureCode);
        } catch (RuntimeException error) {
            log.warn(
                    "Recovered Turn runtime cleanup failed [turnId={}, error={}]",
                    turn.getTurnId(),
                    rootCause(error).getClass().getSimpleName());
        }
        AgentRuntimeEvent event =
                AgentRuntimeEvent.builder()
                        .type(
                                target == TurnStatus.TIMED_OUT
                                        ? AgentRuntimeEvent.Type.TURN_TIMED_OUT
                                        : AgentRuntimeEvent.Type.TURN_FAILED)
                        .turnId(turn.getTurnId())
                        .sessionId(turn.getSessionId())
                        .id(turn.getTurnId())
                        .title(target == TurnStatus.TIMED_OUT ? "执行超时" : "执行实例失联")
                        .status(target.name().toLowerCase(Locale.ROOT))
                        .details(Map.of("errorCode", failureCode))
                        .build();
        try {
            eventPersistence.publish(
                    turn.getOwnerKey(), turn.getSessionId(), turn.getTurnId(), event);
        } catch (TurnEventChannel.EventWriteException error) {
            log.warn(
                    "Recovered Turn terminal event could not be appended to Redis "
                            + "[turnId={}, failureCode={}]",
                    turn.getTurnId(),
                    failureCode);
        }
    }

    /**
     * 解析跨实例控制信号并将它应用到本实例持有的原执行。
     *
     * @param signal 当前执行恢复协调器持有的信号对象，供相应处理步骤使用。
     */
    private void handleControl(TurnControlChannel.CancelSignal signal) {
        TurnExecutionManager.CancelResult result =
                executions.cancel(signal.getOwnerKey(), signal.getSessionId(), signal.getTurnId());
        if (result == TurnExecutionManager.CancelResult.ALREADY_TERMINAL) {
            finishCancellation(signal.getOwnerKey(), signal.getSessionId(), signal.getTurnId());
        }
    }

    /**
     * 向持有原执行的实例发送取消信号。
     *
     * @param turn 当前执行恢复协调器持有的执行对象，供相应处理步骤使用。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void sendCancelControl(AgentTurn turn) {
        try {
            controls.send(
                    turn.getExecutorId(),
                    new TurnControlChannel.CancelSignal(
                            turn.getOwnerKey(), turn.getSessionId(), turn.getTurnId()));
        } catch (RuntimeException error) {
            log.warn("Cancel control enqueue failed [turnId={}]", turn.getTurnId(), error);
            throw new ApplicationError(Code.UNAVAILABLE, "取消请求发送出现异常，尚未确认任务已停止。");
        }
    }

    /**
     * 收敛取消。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private void finishCancellation(String ownerKey, String sessionId, String turnId) {
        sessions.transitionTurn(
                new TransitionTurnCommand(
                        ownerKey,
                        sessionId,
                        turnId,
                        TurnStatus.CANCELLED,
                        null,
                        null,
                        Instant.now(),
                        null,
                        null,
                        null));
    }

    /**
     * 关闭控制轮询与租约检查任务，释放当前协调器持有的调度资源。
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (controlPoller != null) controlPoller.dispose();
        if (leaseReconciler != null) leaseReconciler.dispose();
        if (leases != null) leases.close();
    }
}
