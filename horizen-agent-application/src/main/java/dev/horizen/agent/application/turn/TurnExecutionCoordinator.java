package dev.horizen.agent.application.turn;

import static dev.horizen.agent.common.error.Exceptions.rootCause;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.interaction.ApprovalResolution;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.StartTurnResult;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.AskUserDecision;
import dev.horizen.agent.runtime.api.SessionTurnBusyException;
import dev.horizen.agent.runtime.api.ToolApprovalDecision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** 不依赖传输协议的 Turn 创建、执行、交互恢复和失败处理。 */
public final class TurnExecutionCoordinator {
    /** 当前组件的诊断日志器。 */
    private static final Logger log = LoggerFactory.getLogger(TurnExecutionCoordinator.class);

    /** 当前对象使用的处理策略，决定校验、权限或执行边界。 */
    private final TurnExecutionPolicy policy;

    /** 负责会话占用、执行事实与正式消息的持久化端口。 */
    private final SessionTurnStore sessionTurns;

    /** 共享执行增量与回放的事件通道。 */
    private final TurnEventChannel distributedEvents;

    /** 执行 Agent 模型与工具循环的运行时接口。 */
    private final AgentRuntime runtime;

    /** 持有本地运行订阅、观察与取消句柄的执行管理器。 */
    private final TurnExecutionManager turnExecutions;

    /** 把可信身份、输入资源与交互决定构造成运行时请求的工厂。 */
    private final TurnRequestFactory turnRequests;

    /** 接收模型、工具与执行事件的观测回调。 */
    private final Consumer<AgentRuntimeEvent> eventObserver;

    /** 把运行事件收敛为正式状态、交互记录与回放增量的持久化协调器。 */
    private final TurnEventPersistence eventPersistence;

    /** 重复请求命中原执行时生成观察结果的回放函数。 */
    private final Function<AgentTurn, Flux<AgentRuntimeEvent>> duplicateReplay;

    /** Trace失败Logged的原子状态，供并发更新与统计读取使用。 */
    private final AtomicBoolean traceFailureLogged = new AtomicBoolean();

    /**
     * 创建执行执行协调器，初始化该组件所需的状态、配置或依赖。
     *
     * @param policy 当前对象使用的处理策略，决定校验、权限或执行边界。
     * @param sessionTurns 提供会话执行集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param distributedEvents 当前执行执行协调器持有的分布式事件集合对象，供相应处理步骤使用。
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     * @param executions 当前执行执行协调器持有的执行集合对象，供相应处理步骤使用。
     * @param requests 提供请求集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param eventObserver 当前执行执行协调器持有的事件观察器对象，供相应处理步骤使用。
     * @param persistence 当前执行执行协调器持有的持久化对象，供相应处理步骤使用。
     * @param duplicateReplay 当前执行执行协调器持有的duplicate回放对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public TurnExecutionCoordinator(
            TurnExecutionPolicy policy,
            SessionTurnStore sessionTurns,
            TurnEventChannel distributedEvents,
            AgentRuntime runtime,
            TurnExecutionManager executions,
            TurnRequestFactory requests,
            Consumer<AgentRuntimeEvent> eventObserver,
            TurnEventPersistence persistence,
            Function<AgentTurn, Flux<AgentRuntimeEvent>> duplicateReplay) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sessionTurns = sessionTurns;
        this.distributedEvents = distributedEvents;
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.turnExecutions = Objects.requireNonNull(executions, "executions");
        this.turnRequests = Objects.requireNonNull(requests, "requests");
        this.eventObserver = eventObserver;
        this.eventPersistence = persistence;
        this.duplicateReplay = Objects.requireNonNull(duplicateReplay, "duplicateReplay");
        if ((sessionTurns == null) != (distributedEvents == null)
                || (sessionTurns == null) != (persistence == null)) {
            throw new IllegalArgumentException(
                    "Distributed turn storage, events and persistence must be configured together");
        }
    }

    /**
     * 把持久化的工具参数 JSON 解码成输入映射，拒绝无法解析的旧数据。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 按返回类型约定组织的结果映射。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static Map<String, Object> jsonMap(String value) {
        try {
            return JsonUtils.read(value, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid stored tool arguments", error);
        }
    }

    /** 事件流空闲超时异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。 */
    private static final class StreamIdleTimeoutException extends RuntimeException {
        /** 创建事件流空闲超时异常，初始化该组件所需的状态、配置或依赖。 */
        StreamIdleTimeoutException() {
            super("Agent stream became idle");
        }
    }

    /**
     * 把已提交的澄清回答组装成原执行的恢复请求，复用原执行标识与会话发布。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前执行执行协调器持有的执行对象，供相应处理步骤使用。
     * @param ask 当前执行执行协调器持有的提问对象，供相应处理步骤使用。
     * @param answersJson 回答集合的 JSON 表示，供持久化或协议转换使用。
     */
    public void resumeAskUserTurn(
            ExecutionIdentity identity, AgentTurn turn, AskUserRequest ask, String answersJson) {
        distributedEvents.trim(identity.getOwnerKey(), turn.getTurnId());
        AgentTurnRequest resume =
                buildTurn(
                        identity,
                        new ChatCommand(
                                turn.getSessionId(),
                                "用户已回答问题",
                                "ask-" + ask.getAskUserId(),
                                List.of()),
                        turn.getTurnId(),
                        List.of());
        resume =
                new AgentTurnRequest(
                        resume.getTurnId(),
                        resume.getOwnerKey(),
                        resume.getSessionId(),
                        resume.getMessage(),
                        resume.getApprovalDecisions(),
                        List.of(
                                new AskUserDecision(
                                        ask.getAskUserId(), ask.getToolCallId(), answersJson)),
                        resume.getContextBindings());
        executeTurn(identity, resume, policy.getStreamTimeout());
    }

    /**
     * 把已提交的审批决定转成运行时恢复输入，在原执行的剩余时间预算内继续执行。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前执行执行协调器持有的执行对象，供相应处理步骤使用。
     * @param resolutions 决定结果集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param remaining 剩余的时间配置，供等待、调度或失效判断使用。
     */
    public void resumeApprovedTurn(
            ExecutionIdentity identity,
            AgentTurn turn,
            List<ApprovalResolution> resolutions,
            Duration remaining) {
        distributedEvents.trim(identity.getOwnerKey(), turn.getTurnId());
        List<ToolApprovalDecision> runtimeDecisions =
                resolutions.stream()
                        .map(
                                value -> {
                                    ApprovalRequest request = value.getRequest();
                                    return new ToolApprovalDecision(
                                            request.getToolCallId(),
                                            request.getToolName(),
                                            request.getToolContent(),
                                            jsonMap(request.getToolArgumentsJson()),
                                            value.isApproved());
                                })
                        .toList();
        AgentTurnRequest resumeRequest =
                buildTurn(
                        identity,
                        new ChatCommand(
                                turn.getSessionId(),
                                "用户已处理工具审批",
                                "approval-" + turn.getTurnId(),
                                List.of()),
                        turn.getTurnId(),
                        runtimeDecisions);
        executeTurn(identity, resumeRequest, remaining);
    }

    /**
     * 将单调时钟起点到当前的时间差转换成毫秒。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param startedAt 当前执行或执行段的开始时间。
     * @return 本次操作返回的长整型结果。
     */
    private static long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    /**
     * 将时限转成用于超时提示的分钟或秒文本。
     *
     * @param duration 当前操作使用的时间预算或间隔。
     * @return 本次处理生成或读取的文本。
     */
    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds >= 60 && seconds % 60 == 0) return (seconds / 60) + " 分钟";
        return seconds + " 秒";
    }

    /**
     * 为用户输入创建或复用正式执行。重复请求回放原结果；会话忙或已归档时拒绝新执行。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<AgentRuntimeEvent> start(ExecutionIdentity identity, ChatCommand request) {
        String proposedTurnId = UUID.randomUUID().toString();
        if (sessionTurns != null) {
            Instant now = Instant.now();
            StartTurnResult started =
                    sessionTurns.startTurn(
                            new StartTurnCommand(
                                    identity,
                                    request.getSessionId(),
                                    proposedTurnId,
                                    request.getRequestId(),
                                    policy.getInstanceId(),
                                    "user-" + proposedTurnId,
                                    request.getMessage(),
                                    now,
                                    now.plus(policy.getStreamTimeout()),
                                    now.plus(policy.getLeaseTtl())));
            if (started.getOutcome() == StartTurnResult.Outcome.SESSION_BUSY) {
                return Flux.error(new SessionTurnBusyException());
            }
            if (started.getOutcome() == StartTurnResult.Outcome.SESSION_ARCHIVED) {
                return Flux.error(
                        new ApplicationError(ApplicationError.Code.CONFLICT, "Session 已归档"));
            }
            if (started.getOutcome() == StartTurnResult.Outcome.DUPLICATE) {
                return duplicateReplay.apply(started.getTurn());
            }
            proposedTurnId = started.getTurn().getTurnId();
        }
        AgentTurnRequest turn;
        try {
            turn = buildTurn(identity, request, proposedTurnId, List.of());
        } catch (RuntimeException error) {
            if (sessionTurns != null) {
                sessionTurns.transitionTurn(
                        new TransitionTurnCommand(
                                identity.getOwnerKey(),
                                request.getSessionId(),
                                proposedTurnId,
                                TurnStatus.FAILED,
                                null,
                                null,
                                Instant.now(),
                                rootCause(error).getClass().getSimpleName(),
                                null,
                                null));
            }
            return Flux.error(
                    error instanceof IllegalArgumentException
                            ? new ApplicationError(
                                    ApplicationError.Code.INVALID_ARGUMENT, error.getMessage())
                            : error);
        }
        return executeTurn(identity, turn, policy.getStreamTimeout());
    }

    /**
     * 由宿主持有实际运行订阅，应用空闲与总时限，将运行时事件交给观测、持久化和回放通道。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前执行执行协调器持有的执行对象，供相应处理步骤使用。
     * @param maxDuration 最大耗时的时间配置，供等待、调度或失效判断使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private Flux<AgentRuntimeEvent> executeTurn(
            ExecutionIdentity identity, AgentTurnRequest turn, Duration maxDuration) {
        long startedAt = System.nanoTime();
        ToolTimelineProjection toolHistory = new ToolTimelineProjection(turn);
        AtomicBoolean terminalSeen = new AtomicBoolean();
        Flux<AgentRuntimeEvent> source =
                Flux.defer(
                                () -> {
                                    // 首次下载发布内容也属于执行工作，应在任何网络准备操作前续租。
                                    if (eventPersistence != null) eventPersistence.preparing(turn);
                                    return runtime.stream(turn);
                                })
                        .timeout(
                                policy.getIdleTimeout(),
                                Flux.error(new StreamIdleTimeoutException()))
                        .doOnNext(
                                event -> {
                                    if (TurnEventPersistence.terminal(event.getType())) {
                                        terminalSeen.set(true);
                                    }
                                })
                        .onErrorResume(
                                error -> {
                                    if (terminalSeen.get()) {
                                        return Flux.empty();
                                    }
                                    log.warn(
                                            "Agent execution failure category={} frames={}",
                                            error.getClass().getSimpleName(),
                                            Arrays.stream(error.getStackTrace())
                                                    .limit(20)
                                                    .toList());
                                    boolean timeout = error instanceof StreamIdleTimeoutException;
                                    if (timeout) {
                                        runtime.timeoutCurrentTurn(
                                                identity.getOwnerKey(),
                                                turn.getSessionId(),
                                                turn.getTurnId());
                                    }
                                    String errorCode = rootCause(error).getClass().getSimpleName();
                                    return Flux.just(
                                            AgentRuntimeEvent.builder()
                                                    .type(
                                                            timeout
                                                                    ? AgentRuntimeEvent.Type
                                                                            .TURN_TIMED_OUT
                                                                    : AgentRuntimeEvent.Type
                                                                            .TURN_FAILED)
                                                    .turnId(turn.getTurnId())
                                                    .sessionId(turn.getSessionId())
                                                    .id(turn.getTurnId())
                                                    .title(timeout ? "等待响应超时" : "执行失败")
                                                    .text(
                                                            timeout
                                                                    ? "连续 "
                                                                            + formatDuration(
                                                                                    policy
                                                                                            .getIdleTimeout())
                                                                            + " 没有收到模型或工具事件，执行已停止。"
                                                                    : "Agent 执行失败")
                                                    .status(timeout ? "timed_out" : "failed")
                                                    .details(Map.of("errorCode", errorCode))
                                                    .durationMs(elapsedMs(startedAt))
                                                    .latencyMs(elapsedMs(startedAt))
                                                    .build());
                                })
                        .doFinally(
                                ignored -> {
                                    if (eventPersistence != null)
                                        eventPersistence.stopTrackingLease(turn);
                                });

        try {
            return turnExecutions.start(
                    turn,
                    source,
                    maxDuration,
                    event -> {
                        if (eventObserver != null) {
                            recordTrace(() -> eventObserver.accept(event));
                        }
                        if (eventPersistence == null) return;
                        eventPersistence.observe(identity, turn, event, toolHistory);
                    },
                    (event, error) -> {
                        if (eventPersistence != null) {
                            eventPersistence.failAfterWrite(identity, turn, event, error);
                        }
                    },
                    toolHistory::clear);
        } catch (TurnExecutionManager.TurnAlreadyRunningException error) {
            failStartedTurn(turn, "LOCAL_TURN_ALREADY_RUNNING");
            return Flux.error(new SessionTurnBusyException());
        } catch (RuntimeException error) {
            failStartedTurn(turn, rootCause(error).getClass().getSimpleName());
            return Flux.error(error);
        }
    }

    /**
     * 通过宿主请求工厂绑定身份、输入资源与恢复决定，构造本次运行时请求。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param approvalDecisions 当前恢复请求提交的审批决定集合。
     * @return 本次操作返回的Agent执行请求结果。
     */
    private AgentTurnRequest buildTurn(
            ExecutionIdentity identity,
            ChatCommand request,
            String turnId,
            List<ToolApprovalDecision> approvalDecisions) {
        return turnRequests.create(identity, request, turnId, approvalDecisions);
    }

    /**
     * 启动记录已经提交但后续准备失败时，将对应原执行收敛为失败状态。
     *
     * @param turn 当前执行执行协调器持有的执行对象，供相应处理步骤使用。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     */
    private void failStartedTurn(AgentTurnRequest turn, String failureCode) {
        if (sessionTurns == null) {
            return;
        }
        sessionTurns.transitionTurn(
                new TransitionTurnCommand(
                        turn.getOwnerKey(),
                        turn.getSessionId(),
                        turn.getTurnId(),
                        TurnStatus.FAILED,
                        null,
                        null,
                        Instant.now(),
                        failureCode,
                        null,
                        null));
    }

    /**
     * 调用执行观测回调；观测写入失败只记录安全诊断，不改变执行结果。
     * 并发状态更新包含比较交换操作。
     *
     * @param action 在当前处理边界中执行的操作。
     */
    private void recordTrace(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            if (traceFailureLogged.compareAndSet(false, true)) {
                log.warn(
                        "Local execution trace could not be written ({})",
                        error.getClass().getSimpleName());
            }
        }
    }
}
