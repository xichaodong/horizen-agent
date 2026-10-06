package dev.horizen.agent.adapter.agentscope.runtime;

import dev.horizen.agent.adapter.agentscope.workspace.snapshot.DurableWorkspaceSandboxContext;
import dev.horizen.agent.adapter.agentscope.workspace.snapshot.SandboxSnapshotCheckpoint;
import dev.horizen.agent.context.HistoryRecoveryScope;
import dev.horizen.agent.domain.artifact.ArtifactEventCollector;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.artifact.ArtifactLineageContext;
import dev.horizen.agent.domain.askuser.AskUserEventCollector;
import dev.horizen.agent.domain.presentation.PresentationEventCollector;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.runtime.api.AgentContextBinding;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.SessionExecutionState;
import dev.horizen.agent.runtime.api.SessionTurnBusyException;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * AgentScope Harness 实现；隔离键由宿主生成，本类只负责一致地应用它。
 */
public final class HarnessAgentRuntime implements AgentRuntime {
    /**
     * 当前配置的 Agent 实例，承担模型与工具循环执行。
     */
    private final HarnessAgent agent;

    /**
     * 在共享状态中维护会话活跃执行与状态转换的协调器。
     */
    private final AgentScopeSessionExecutionCoordinator sessionExecution;

    /**
     * 把 AgentScope 原生事件转换为宿主事件的映射器。
     */
    private final HarnessAgentEventMapper eventMapper;

    /**
     * 进入执行前补充工作区与宿主绑定上下文的回调。
     */
    private final Consumer<RuntimeContext> prepareContext;

    /**
     * 保存会话最近工作区快照引用的仓储。
     */
    private final WorkspaceSnapshotPointerRepository snapshotPointers;

    /**
     * 活跃Contexts的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<String, RuntimeContext> activeContexts = new ConcurrentHashMap<>();

    /**
     * 本组件使用的 {@code Object} 状态或依赖，用于 lifecycle 的处理。
     */
    private final Object lifecycle = new Object();

    /**
     * 组件是否已关闭，用于避免重复释放或继续接收新工作。
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建HarnessAgent运行时，初始化该组件所需的状态、配置或依赖。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     */
    public HarnessAgentRuntime(HarnessAgent agent) {
        this(agent, context -> {
        }, null);
    }

    /**
     * 创建HarnessAgent运行时，初始化该组件所需的状态、配置或依赖。
     *
     * @param agent            当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param prepareContext   当前HarnessAgent运行时持有的准备上下文对象，供相应处理步骤使用。
     * @param snapshotPointers 提供快照指针集合能力的依赖，具体实现由当前组件的组装方传入。
     */
    public HarnessAgentRuntime(
            HarnessAgent agent,
            Consumer<RuntimeContext> prepareContext,
            WorkspaceSnapshotPointerRepository snapshotPointers) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.prepareContext = Objects.requireNonNull(prepareContext, "prepareContext");
        this.snapshotPointers = snapshotPointers;
        this.eventMapper = new HarnessAgentEventMapper(agent);
        this.sessionExecution =
                new AgentScopeSessionExecutionCoordinator(
                        agent.getStateStore() != null
                                ? agent.getStateStore()
                                : new InMemoryAgentStateStore());
    }

    /**
     * 按所有者和会话隔离的后台子 Agent 任务宿主视图。
     */
    public List<Map<String, Object>> listSubtasks(String ownerKey, String sessionId) {
        var repository = agent.getTaskRepository();
        if (repository == null) return List.of();
        RuntimeContext context =
                RuntimeContext.builder().userId(ownerKey).sessionId(sessionId).build();
        return repository.listTasks(context, sessionId, null).stream()
                .map(
                        task -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("taskId", task.getTaskId());
                            value.put("agentId", task.getAgentId());
                            value.put(
                                    "status", task.getTaskStatus().name().toLowerCase(Locale.ROOT));
                            value.put("createdAt", task.getCreatedAt().toString());
                            if (task.getResult() != null) value.put("result", task.getResult());
                            if (task.getError() != null)
                                value.put("error", task.getError().getMessage());
                            return value;
                        })
                .toList();
    }

    /**
     * 在当前归属与会话范围内请求停止指定的委派任务。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param taskId    任务的标识，用于关联相应记录或执行。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean cancelSubtask(String ownerKey, String sessionId, String taskId) {
        var repository = agent.getTaskRepository();
        if (repository == null) return false;
        return repository.cancelTask(
                RuntimeContext.builder().userId(ownerKey).sessionId(sessionId).build(),
                sessionId,
                taskId);
    }

    /**
     * 应用绑定。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param binding 当前HarnessAgent运行时持有的绑定对象，供相应处理步骤使用。
     */
    private static <T> void applyBinding(
            RuntimeContext.Builder context, AgentContextBinding<T> binding) {
        context.put(binding.getType(), binding.getValue());
    }

    /**
     * 把宿主请求映射到 AgentScope 上下文与输入，持有执行状态并转换为宿主事件流。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentRuntimeEvent> stream(AgentTurnRequest request) {
        Objects.requireNonNull(request, "request");
        return Flux.defer(
                () -> {
                    if (closed.get())
                        return Flux.error(new IllegalStateException("Agent runtime is closed"));
                    Instant startedAt = Instant.now();
                    boolean accepted =
                            !request.getAskUserDecisions().isEmpty()
                                    ? sessionExecution.resumeAskUser(
                                    request.getOwnerKey(),
                                    request.getSessionId(),
                                    request.getTurnId())
                                    : request.getApprovalDecisions().isEmpty()
                                    ? sessionExecution
                                    .tryStart(
                                            request.getOwnerKey(),
                                            request.getSessionId(),
                                            request.getTurnId(),
                                            startedAt)
                                    .isPresent()
                                    : sessionExecution.resumeApproval(
                                    request.getOwnerKey(),
                                    request.getSessionId(),
                                    request.getTurnId());
                    if (!accepted) {
                        return Flux.error(new SessionTurnBusyException());
                    }
                    long turnStartedAt = System.nanoTime();
                    Map<String, Long> stepStarts = new HashMap<>();
                    AtomicBoolean sawCompletedEvent = new AtomicBoolean();
                    AtomicBoolean terminalRecorded = new AtomicBoolean();
                    ArtifactEventCollector artifactEvents = new ArtifactEventCollector();
                    PresentationEventCollector presentationEvents =
                            new PresentationEventCollector();
                    AskUserEventCollector askUserEvents = new AskUserEventCollector();
                    RuntimeContext.Builder context =
                            RuntimeContext.builder()
                                    .userId(request.getOwnerKey())
                                    .sessionId(request.getSessionId())
                                    .put(
                                            ArtifactExecutionContext.class,
                                            new ArtifactExecutionContext(request.getTurnId()))
                                    .put(ArtifactLineageContext.class, new ArtifactLineageContext())
                                    .put(ArtifactEventCollector.class, artifactEvents)
                                    .put(PresentationEventCollector.class, presentationEvents)
                                    .put(AskUserEventCollector.class, askUserEvents)
                                    .put(
                                            ToolInvocationScope.class,
                                            new ToolInvocationScope(
                                                    request.getOwnerKey(),
                                                    request.getSessionId(),
                                                    request.getTurnId()))
                                    .put(
                                            HistoryRecoveryScope.class,
                                            new HistoryRecoveryScope(
                                                    request.getApprovalDecisions().isEmpty()
                                                            && request.getAskUserDecisions()
                                                            .isEmpty()))
                                    .put(
                                            AbstractFilesystem.class,
                                            agent.getWorkspaceManager().getFilesystem());
                    if (!request.getAttachments().isEmpty()) {
                        context.put(
                                MultimodalTurnInput.class,
                                new MultimodalTurnInput(request.getAttachments()));
                    }
                    request.getContextBindings().forEach(binding -> applyBinding(context, binding));
                    context.put(
                            "agent.invocationKind",
                            !request.getAskUserDecisions().isEmpty()
                                    ? "ask_user_resume"
                                    : !request.getApprovalDecisions().isEmpty()
                                    ? "approval_resume"
                                    : "turn");
                    RuntimeContext runtimeContext = context.build();
                    String invocationKey = UUID.randomUUID().toString();
                    AtomicBoolean released = new AtomicBoolean();
                    Runnable releaseContext =
                            () -> {
                                if (!released.compareAndSet(false, true)) return;
                                try {
                                    DurableWorkspaceSandboxContext.release(runtimeContext);
                                } finally {
                                    synchronized (lifecycle) {
                                        activeContexts.remove(invocationKey);
                                        lifecycle.notifyAll();
                                    }
                                }
                            };
                    Msg input = RuntimeInputMapper.buildInputMessage(request);

                    AgentRuntimeEvent started =
                            RuntimeEventFactory.event(
                                            request,
                                            AgentRuntimeEvent.Type.TURN_STARTED,
                                            request.getTurnId())
                                    .status("running")
                                    .build();
                    Flux<AgentRuntimeEvent> execution =
                            Flux.defer(
                                            () -> {
                                                prepareContext.accept(runtimeContext);
                                                return agent.streamEvents(input, runtimeContext);
                                            })
                                    .flatMapIterable(
                                            source -> {
                                                List<AgentRuntimeEvent> events =
                                                        eventMapper.map(
                                                                request,
                                                                source,
                                                                turnStartedAt,
                                                                stepStarts,
                                                                artifactEvents,
                                                                presentationEvents,
                                                                askUserEvents,
                                                                runtimeContext);
                                                if (events.stream()
                                                        .anyMatch(
                                                                event ->
                                                                        event.getType() == AgentRuntimeEvent
                                                                                .Type
                                                                                .TURN_COMPLETED
                                                                                || event.getType()
                                                                                == AgentRuntimeEvent
                                                                                .Type
                                                                                .APPROVAL_REQUIRED
                                                                                || event.getType()
                                                                                == AgentRuntimeEvent
                                                                                .Type
                                                                                .ASK_USER_REQUIRED)) {
                                                    SandboxSnapshotCheckpoint.save(
                                                            runtimeContext,
                                                            agent.getStateStore(),
                                                            agent.getName(),
                                                            snapshotPointers);
                                                }
                                                return events;
                                            })
                                    .doOnNext(
                                            event -> {
                                                if (event.getType()
                                                        == AgentRuntimeEvent.Type.TURN_COMPLETED) {
                                                    sawCompletedEvent.set(true);
                                                    // 宿主收到完成事件后可立即释放源订阅，须先提交完成状态。
                                                    if (terminalRecorded.compareAndSet(
                                                            false, true)) {
                                                        sessionExecution.finish(
                                                                request.getOwnerKey(),
                                                                request.getSessionId(),
                                                                request.getTurnId(),
                                                                TurnStatus.COMPLETED,
                                                                Instant.now(),
                                                                null);
                                                    }
                                                }
                                                if (event.getType()
                                                        == AgentRuntimeEvent.Type
                                                        .APPROVAL_REQUIRED) {
                                                    terminalRecorded.set(true);
                                                    sessionExecution.pauseForApproval(
                                                            request.getOwnerKey(),
                                                            request.getSessionId(),
                                                            request.getTurnId());
                                                }
                                                if (event.getType()
                                                        == AgentRuntimeEvent.Type
                                                        .ASK_USER_REQUIRED) {
                                                    terminalRecorded.set(true);
                                                    sessionExecution.pauseForAskUser(
                                                            request.getOwnerKey(),
                                                            request.getSessionId(),
                                                            request.getTurnId());
                                                }
                                                if (event.getType()
                                                        == AgentRuntimeEvent.Type
                                                        .TURN_FAILED
                                                        || event.getType()
                                                        == AgentRuntimeEvent.Type
                                                        .TURN_CANCELLED
                                                        || event.getType()
                                                        == AgentRuntimeEvent.Type
                                                        .TURN_TIMED_OUT) {
                                                    terminalRecorded.set(true);
                                                    TurnStatus status =
                                                            event.getType()
                                                                    == AgentRuntimeEvent
                                                                    .Type
                                                                    .TURN_CANCELLED
                                                                    ? TurnStatus.CANCELLED
                                                                    : event.getType()
                                                                    == AgentRuntimeEvent
                                                                    .Type
                                                                    .TURN_TIMED_OUT
                                                                    ? TurnStatus.TIMED_OUT
                                                                    : TurnStatus.FAILED;
                                                    String code =
                                                            event.getDetails()
                                                                    instanceof
                                                                    Map<?, ?> details
                                                                    ? String.valueOf(
                                                                    details.get(
                                                                            "errorCode"))
                                                                    : "EXECUTION_ERROR";
                                                    sessionExecution.finish(
                                                            request.getOwnerKey(),
                                                            request.getSessionId(),
                                                            request.getTurnId(),
                                                            status,
                                                            Instant.now(),
                                                            code);
                                                }
                                            })
                                    .concatWith(
                                            Flux.defer(
                                                    () -> {
                                                        if (terminalRecorded.get()
                                                                || sawCompletedEvent.get())
                                                            return Flux.empty();
                                                        terminalRecorded.set(true);
                                                        sessionExecution.finish(
                                                                request.getOwnerKey(),
                                                                request.getSessionId(),
                                                                request.getTurnId(),
                                                                TurnStatus.FAILED,
                                                                Instant.now(),
                                                                "MISSING_TERMINAL_RESULT");
                                                        return Flux.just(
                                                                RuntimeEventFactory.event(
                                                                                request,
                                                                                AgentRuntimeEvent
                                                                                        .Type
                                                                                        .TURN_FAILED,
                                                                                request.getTurnId())
                                                                        .text("本轮执行异常结束，未收到完整结果。")
                                                                        .status("failed")
                                                                        .details(
                                                                                Map.of(
                                                                                        "errorCode",
                                                                                        "MISSING_TERMINAL_RESULT"))
                                                                        .build());
                                                    }))
                                    .onErrorResume(
                                            error -> {
                                                TurnStatus status =
                                                        RuntimeFailureClassifier.isTimeout(error)
                                                                ? TurnStatus.TIMED_OUT
                                                                : TurnStatus.FAILED;
                                                String failureCode =
                                                        error
                                                                instanceof
                                                                SandboxSnapshotCheckpoint
                                                                        .SnapshotCheckpointException
                                                                ? "WORKSPACE_SNAPSHOT_FAILED"
                                                                : status == TurnStatus.TIMED_OUT
                                                                ? "TURN_TIMEOUT"
                                                                : RuntimeFailureClassifier
                                                                .executionFailureCode(
                                                                        stepStarts);
                                                terminalRecorded.set(true);
                                                sessionExecution.finish(
                                                        request.getOwnerKey(),
                                                        request.getSessionId(),
                                                        request.getTurnId(),
                                                        status,
                                                        Instant.now(),
                                                        failureCode);
                                                AgentRuntimeEvent.Type type =
                                                        status == TurnStatus.TIMED_OUT
                                                                ? AgentRuntimeEvent.Type
                                                                .TURN_TIMED_OUT
                                                                : AgentRuntimeEvent.Type
                                                                .TURN_FAILED;
                                                AgentRuntimeEvent failed =
                                                        RuntimeEventFactory.event(
                                                                        request,
                                                                        type,
                                                                        request.getTurnId())
                                                                .status(
                                                                        status.name()
                                                                                .toLowerCase(
                                                                                        Locale
                                                                                                .ROOT))
                                                                .details(
                                                                        Map.of(
                                                                                "errorCode",
                                                                                failureCode,
                                                                                "causeType",
                                                                                RuntimeFailureClassifier
                                                                                        .errorCode(
                                                                                                error)))
                                                                .durationMs(
                                                                        EventTiming.elapsedMs(
                                                                                turnStartedAt))
                                                                .latencyMs(
                                                                        EventTiming.elapsedMs(
                                                                                turnStartedAt))
                                                                .build();
                                                return Flux.concat(
                                                        Flux.just(failed),
                                                        Flux.<AgentRuntimeEvent>error(error));
                                            })
                                    .doOnComplete(
                                            () -> {
                                                if (terminalRecorded.compareAndSet(false, true)) {
                                                    sessionExecution.finish(
                                                            request.getOwnerKey(),
                                                            request.getSessionId(),
                                                            request.getTurnId(),
                                                            sawCompletedEvent.get()
                                                                    ? TurnStatus.COMPLETED
                                                                    : TurnStatus.FAILED,
                                                            Instant.now(),
                                                            sawCompletedEvent.get()
                                                                    ? null
                                                                    : "MISSING_TERMINAL_RESULT");
                                                }
                                            })
                                    .doOnTerminate(releaseContext)
                                    .doOnCancel(releaseContext);
                    List<AgentRuntimeEvent> resolvedAskUsers =
                            request.getAskUserDecisions().stream()
                                    .map(
                                            decision ->
                                                    RuntimeEventFactory.event(
                                                                    request,
                                                                    AgentRuntimeEvent.Type
                                                                            .ASK_USER_RESOLVED,
                                                                    decision.getAskUserId())
                                                            .status("running")
                                                            .toolName("ask_user")
                                                            .details(
                                                                    Map.of(
                                                                            "askUserId",
                                                                            decision
                                                                                    .getAskUserId()))
                                                            .build())
                                    .toList();
                    synchronized (lifecycle) {
                        if (closed.get())
                            return Flux.error(new IllegalStateException("Agent runtime is closed"));
                        activeContexts.put(invocationKey, runtimeContext);
                    }
                    return Flux.concat(
                                    Flux.just(started),
                                    Flux.fromIterable(resolvedAskUsers),
                                    execution)
                            .doOnCancel(
                                    () -> {
                                        agent.interrupt(
                                                request.getOwnerKey(), request.getSessionId());
                                        if (terminalRecorded.compareAndSet(false, true)) {
                                            sessionExecution.finish(
                                                    request.getOwnerKey(),
                                                    request.getSessionId(),
                                                    request.getTurnId(),
                                                    TurnStatus.CANCELLED,
                                                    Instant.now(),
                                                    null);
                                        }
                                    });
                });
    }

    /**
     * 读取会话执行的当前值。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return {@link #sessionExecution} 中保存的值。
     */
    @Override
    public Optional<SessionExecutionState> sessionExecution(String ownerKey, String sessionId) {
        return sessionExecution.get(ownerKey, sessionId);
    }

    /**
     * 核对原执行并请求 AgentScope 中断，记录宿主侧的超时结果。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean timeoutCurrentTurn(String ownerKey, String sessionId) {
        agent.interrupt(ownerKey, sessionId);
        return sessionExecution.timeoutCurrent(ownerKey, sessionId, Instant.now(), "HOST_TIMEOUT");
    }

    /**
     * 核对原执行并请求 AgentScope 中断，记录宿主侧的超时结果。
     *
     * @param ownerKey       宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId      会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param expectedTurnId 调用方期望操作的执行标识，用于防止旧页面误操作后续执行。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean timeoutCurrentTurn(String ownerKey, String sessionId, String expectedTurnId) {
        SessionExecutionState current = sessionExecution.get(ownerKey, sessionId).orElse(null);
        if (current == null
                || current.getStatus().isTerminal()
                || !current.getTurnId().equals(expectedTurnId)) {
            return false;
        }
        agent.interrupt(ownerKey, sessionId);
        return sessionExecution.forceFinish(
                ownerKey,
                sessionId,
                expectedTurnId,
                TurnStatus.TIMED_OUT,
                Instant.now(),
                "HOST_TIMEOUT");
    }

    /**
     * 核对期望执行标识后中断原执行，避免停止同一会话中的后续任务。
     *
     * @param ownerKey       宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId      会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param expectedTurnId 调用方期望操作的执行标识，用于防止旧页面误操作后续执行。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean interruptCurrentTurn(String ownerKey, String sessionId, String expectedTurnId) {
        SessionExecutionState current = sessionExecution.get(ownerKey, sessionId).orElse(null);
        if (current == null
                || current.getStatus().isTerminal()
                || !current.getTurnId().equals(expectedTurnId)) {
            return false;
        }
        agent.interrupt(ownerKey, sessionId);
        return sessionExecution.forceFinish(
                ownerKey, sessionId, expectedTurnId, TurnStatus.CANCELLED, Instant.now(), null);
    }

    /**
     * 在执行实例失联等宿主失败情形下结束共享运行状态。
     *
     * @param ownerKey       宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId      会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param expectedTurnId 调用方期望操作的执行标识，用于防止旧页面误操作后续执行。
     * @param failureCode    机器可识别的失败分类，供状态恢复与错误展示使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean failCurrentTurn(
            String ownerKey, String sessionId, String expectedTurnId, String failureCode) {
        SessionExecutionState current = sessionExecution.get(ownerKey, sessionId).orElse(null);
        if (current == null
                || current.getStatus().isTerminal()
                || !current.getTurnId().equals(expectedTurnId)) {
            return false;
        }
        agent.interrupt(ownerKey, sessionId);
        return sessionExecution.forceFinish(
                ownerKey, sessionId, expectedTurnId, TurnStatus.FAILED, Instant.now(), failureCode);
    }

    /**
     * 停止运行时接收新工作，释放它持有的活动执行与相关运行资源。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        activeContexts
                .values()
                .forEach(context -> agent.interrupt(context.getUserId(), context.getSessionId()));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        synchronized (lifecycle) {
            while (!activeContexts.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        agent.close();
    }
}
