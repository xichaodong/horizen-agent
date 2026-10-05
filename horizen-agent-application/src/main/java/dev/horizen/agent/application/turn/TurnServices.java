package dev.horizen.agent.application.turn;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.interaction.ApprovalResolution;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.presentation.PresentationStore;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.execution.turn.TurnTimelineStore;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import lombok.Value;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** 管理执行服务并借用 Runtime；传入的 Runtime 由宿主关闭。 */
public final class TurnServices implements AutoCloseable {
    /** 执行 Agent 模型与工具循环的运行时接口。 */
    private final AgentRuntime runtime;

    /** 本组件使用的运行或资源管理器，协调对应句柄的生命周期。 */
    private final TurnExecutionManager manager;

    /** 单次执行的创建、运行与交互恢复协调器。 */
    private final TurnExecutionCoordinator execution;

    /** 执行失联、取消与终态回放的协调器。 */
    private final TurnRecoveryCoordinator recovery;

    /** 将运行事件与终态写入正式历史的持久化协调器。 */
    private final PersistencePorts persistence;

    /** 组件是否已关闭，用于避免重复释放或继续接收新工作。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param policy 当前对象使用的处理策略，决定校验、权限或执行边界。
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     * @param requests 提供请求集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param observer 接收结果或事件的回调。
     * @return 本次操作返回的执行服务集合结果。
     */
    public static TurnServices standalone(
            TurnExecutionPolicy policy,
            AgentRuntime runtime,
            TurnRequestFactory requests,
            Consumer<AgentRuntimeEvent> observer) {
        return new TurnServices(policy, runtime, requests, observer, null, null, null, null, null);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param policy 当前对象使用的处理策略，决定校验、权限或执行边界。
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     * @param requests 提供请求集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param observer 接收结果或事件的回调。
     * @param persistence 当前执行服务集合持有的持久化对象，供相应处理步骤使用。
     * @param recovery 当前执行服务集合持有的恢复对象，供相应处理步骤使用。
     * @param leases 当前执行服务集合持有的租约集合对象，供相应处理步骤使用。
     * @param controls 当前执行服务集合持有的控制集合对象，供相应处理步骤使用。
     * @param encoder 将输入转换为目标结果的函数。
     * @return 本次操作返回的执行服务集合结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static TurnServices distributed(
            TurnExecutionPolicy policy,
            AgentRuntime runtime,
            TurnRequestFactory requests,
            Consumer<AgentRuntimeEvent> observer,
            PersistencePorts persistence,
            TurnRecoveryPolicy recovery,
            LeaseRenewalPolicy leases,
            TurnControlChannel controls,
            Function<AgentRuntimeEvent, String> encoder) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(controls, "controls");
        Objects.requireNonNull(encoder, "encoder");
        if (!recovery.getInstanceId().equals(policy.getInstanceId())) {
            throw new IllegalArgumentException(
                    "Execution and recovery instance identities must match");
        }
        return new TurnServices(
                policy,
                runtime,
                requests,
                observer,
                persistence,
                recovery,
                leases,
                controls,
                encoder);
    }

    /**
     * 创建执行服务集合，初始化该组件所需的状态、配置或依赖。
     *
     * @param policy 当前对象使用的处理策略，决定校验、权限或执行边界。
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     * @param requests 提供请求集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param observer 接收结果或事件的回调。
     * @param persistence 当前执行服务集合持有的持久化对象，供相应处理步骤使用。
     * @param recoveryPolicy 当前执行服务集合持有的恢复策略对象，供相应处理步骤使用。
     * @param leasePolicy 当前执行服务集合持有的租约策略对象，供相应处理步骤使用。
     * @param controls 当前执行服务集合持有的控制集合对象，供相应处理步骤使用。
     * @param encoder 将输入转换为目标结果的函数。
     */
    private TurnServices(
            TurnExecutionPolicy policy,
            AgentRuntime runtime,
            TurnRequestFactory requests,
            Consumer<AgentRuntimeEvent> observer,
            PersistencePorts persistence,
            TurnRecoveryPolicy recoveryPolicy,
            LeaseRenewalPolicy leasePolicy,
            TurnControlChannel controls,
            Function<AgentRuntimeEvent, String> encoder) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.persistence = persistence;
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(requests, "requests");
        manager = new TurnExecutionManager(runtime);
        TurnEventPersistence events = null;
        if (persistence == null) {
            recovery = null;
        } else {
            var renewer =
                    new InstanceLeaseRenewer(
                            persistence.getSessions(),
                            policy.getInstanceId(),
                            policy.getLeaseTtl(),
                            recoveryPolicy.getLeaseHeartbeat(),
                            leasePolicy);
            events =
                    new TurnEventPersistence(
                            persistence.getSessions(),
                            persistence.getApprovals(),
                            persistence.getPresentations(),
                            persistence.getTimeline(),
                            persistence.getEvents(),
                            runtime,
                            encoder,
                            turn -> renewer.track(turn.getOwnerKey(), turn.getTurnId()),
                            turn -> renewer.untrack(turn.getOwnerKey(), turn.getTurnId()));
            recovery =
                    new TurnRecoveryCoordinator(
                            controls,
                            recoveryPolicy,
                            persistence.getSessions(),
                            runtime,
                            manager,
                            renewer,
                            events);
        }
        execution =
                new TurnExecutionCoordinator(
                        policy,
                        persistence == null ? null : persistence.getSessions(),
                        persistence == null ? null : persistence.getEvents(),
                        runtime,
                        manager,
                        requests,
                        observer,
                        events,
                        recovery == null
                                ? turn ->
                                        Flux.error(
                                                new IllegalStateException(
                                                        "Standalone turns have no durable replay"))
                                : recovery::replayTerminal);
    }

    /** 启动恢复。 */
    public void startRecovery() {
        requireOpen();
        if (recovery != null) recovery.start();
    }

    /**
     * 启动执行服务集合。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<AgentRuntimeEvent> start(ExecutionIdentity identity, ChatCommand request) {
        requireOpen();
        return execution.start(identity, request);
    }

    /**
     * 恢复提问用户。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前执行服务集合持有的执行对象，供相应处理步骤使用。
     * @param ask 当前执行服务集合持有的提问对象，供相应处理步骤使用。
     * @param answersJson 回答集合的 JSON 表示，供持久化或协议转换使用。
     */
    public void resumeAskUser(
            ExecutionIdentity identity, AgentTurn turn, AskUserRequest ask, String answersJson) {
        requireOpen();
        requireDistributed();
        execution.resumeAskUserTurn(identity, turn, ask, answersJson);
    }

    /**
     * 恢复批准。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前执行服务集合持有的执行对象，供相应处理步骤使用。
     * @param resolutions 决定结果集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param remaining 剩余的时间配置，供等待、调度或失效判断使用。
     */
    public void resumeApproved(
            ExecutionIdentity identity,
            AgentTurn turn,
            List<ApprovalResolution> resolutions,
            Duration remaining) {
        requireOpen();
        requireDistributed();
        execution.resumeApprovedTurn(identity, turn, resolutions, remaining);
    }

    /**
     * 计算或取得本方法声明的结果，供当前TurnServices处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param afterEventSequence 客户端已经观察到的执行事件游标，重连时从其后继续回放。
     * @param afterTimelineSequence 当前执行服务集合使用的处理后时间线序号，供其处理与状态记录使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Flux<AgentRuntimeEvent> replay(
            ExecutionIdentity identity,
            String sessionId,
            String turnId,
            long afterEventSequence,
            long afterTimelineSequence) {
        requireOpen();
        if (persistence == null)
            return manager.subscribe(identity.getOwnerKey(), sessionId, turnId);
        AgentTurn turn =
                persistence
                        .getSessions()
                        .findTurn(identity.getOwnerKey(), turnId)
                        .orElseThrow(
                                () ->
                                        new ApplicationError(
                                                ApplicationError.Code.NOT_FOUND, "Turn 不存在"));
        if (!turn.getSessionId().equals(sessionId))
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "Turn 不属于当前 Session");
        if (turn.getStatus().isTerminal())
            return afterTimelineSequence > 0 ? Flux.empty() : recovery.replayTerminal(turn);
        return persistence.getEvents().replay(identity.getOwnerKey(), turnId, afterEventSequence);
    }

    /**
     * 取消执行服务集合。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void cancel(ExecutionIdentity identity, String sessionId, String turnId) {
        requireOpen();
        if (recovery != null) {
            recovery.cancel(identity, sessionId, turnId);
            return;
        }
        switch (manager.cancel(identity.getOwnerKey(), sessionId, turnId)) {
            case TURN_CHANGED ->
                    throw new ApplicationError(ApplicationError.Code.CONFLICT, "当前 Turn 已变化，未执行取消");
            case NOT_FOUND ->
                    throw new ApplicationError(
                            ApplicationError.Code.NOT_FOUND, "Session 没有可取消的 Turn");
            case NOT_OWNED ->
                    throw new ApplicationError(ApplicationError.Code.CONFLICT, "当前实例不持有该 Turn");
            default -> {}
        }
    }

    /**
     * 检查settled对应的条件，供调用方选择后续处理分支。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean settled(ExecutionIdentity identity, String sessionId) {
        requireOpen();
        return !manager.hasActiveTurn(identity.getOwnerKey(), sessionId)
                && runtime.sessionExecution(identity.getOwnerKey(), sessionId)
                        .map(
                                state ->
                                        state.getStatus() != TurnStatus.RUNNING
                                                && state.getStatus() != TurnStatus.CANCELLING)
                        .orElse(true);
    }

    /**
     * 计算或取得本方法声明的结果，供当前TurnServices处理步骤使用。
     *
     * @return 本次操作返回的状态结果。
     */
    public InstanceLeaseRenewer.Status leaseStatus() {
        return recovery == null ? new InstanceLeaseRenewer.Status() : recovery.status();
    }

    /**
     * 取得并校验开启。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireOpen() {
        if (closed.get()) throw new ApplicationError(ApplicationError.Code.UNAVAILABLE, "执行服务正在关闭");
    }

    /**
     * 取得并校验分布式。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireDistributed() {
        if (persistence == null)
            throw new ApplicationError(ApplicationError.Code.UNAVAILABLE, "此操作需要持久化执行存储");
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     * 并发状态更新包含比较交换操作。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (recovery != null) recovery.close();
        } finally {
            manager.close();
        }
    }

    /** 持久化执行所需的配套接口，必须同时提供。 */
    @Value
    public static class PersistencePorts {
        /** 会话对象或会话索引，按相应的归属键定位数据。 */
        SessionTurnStore sessions;

        /** 审批存储或待处理审批集合，用于原执行的暂停与恢复。 */
        ApprovalStore approvals;

        /** 需要持久化或展示的结构化呈现块集合。 */
        PresentationStore presentations;

        /** 存储正式过程事件的时间线端口，供持久化与刷新恢复使用。 */
        TurnTimelineStore timeline;

        /** 当前执行或历史事件集合，供持久化、回放与观测使用。 */
        TurnEventChannel events;

        /**
         * 创建持久化Ports，初始化该组件所需的状态、配置或依赖。
         *
         * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
         * @param approvals 审批存储或待处理审批集合，用于原执行的暂停与恢复。
         * @param presentations 需要持久化或展示的结构化呈现块集合。
         * @param timeline 提供时间线能力的依赖，具体实现由当前组件的组装方传入。
         * @param events 当前执行或历史事件集合，供持久化、回放与观测使用。
         */
        public PersistencePorts(
                SessionTurnStore sessions,
                ApprovalStore approvals,
                PresentationStore presentations,
                TurnTimelineStore timeline,
                TurnEventChannel events) {
            this.sessions = Objects.requireNonNull(sessions, "sessions");
            this.approvals = Objects.requireNonNull(approvals, "approvals");
            this.presentations = Objects.requireNonNull(presentations, "presentations");
            this.timeline = Objects.requireNonNull(timeline, "timeline");
            this.events = Objects.requireNonNull(events, "events");
        }
    }
}
