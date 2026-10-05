package dev.horizen.agent.web.execution;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.interaction.ApprovalResolution;
import dev.horizen.agent.application.turn.ChatCommand;
import dev.horizen.agent.application.turn.InstanceLeaseRenewer;
import dev.horizen.agent.application.turn.TurnExecutionManager;
import dev.horizen.agent.application.turn.TurnServices;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.SessionTurnBusyException;
import dev.horizen.agent.web.api.AgentApiMapper;
import dev.horizen.agent.web.api.AgentRequestValidator;
import dev.horizen.agent.web.api.AgentSessionApiService;
import dev.horizen.agent.web.api.ApiException;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.chat.ChatApi.ChatRequest;
import dev.horizen.agent.web.api.chat.ChatApi.ChatResponse;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.api.session.SessionApi.SessionCancelRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionSubscribeRequest;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import lombok.Getter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 协调单个 Turn 的生命周期、回放、取消和租约恢复。 */
public final class AgentTurnCoordinator implements AutoCloseable {
    /** 将未知执行故障记录在服务端，客户端只接收固定的错误提示。 */
    private static final Logger log = LoggerFactory.getLogger(AgentTurnCoordinator.class);

    /** 组件是否已关闭，用于避免重复释放或继续接收新工作。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 单次执行流允许持续的最长时间。 */
    @Getter private final Duration streamTimeout;

    /** 当前宿主组装的执行、控制、恢复和持久化服务集合。 */
    private final Optional<TurnServices> services;

    /** 把运行时事件与领域状态转换为 Web DTO 的映射器。 */
    private final AgentApiMapper apiMapper;

    /** 会话目录、执行状态与历史查询接口的应用服务。 */
    private final AgentSessionApiService sessionApi;

    /** 从诊断消息中去除已配置服务凭据的清理器。 */
    private final Function<String, String> redactor;

    /**
     * 创建Agent执行协调器，初始化该组件所需的状态、配置或依赖。
     *
     * @param streamTimeout 单次执行流允许持续的最长时间。
     * @param services 当前Agent执行协调器持有的服务集合对象，供相应处理步骤使用。
     * @param apiMapper 提供API映射器能力的依赖，具体实现由当前组件的组装方传入。
     * @param sessionApi 提供会话API能力的依赖，具体实现由当前组件的组装方传入。
     * @param redactor 当前Agent执行协调器持有的脱敏器对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public AgentTurnCoordinator(
            Duration streamTimeout,
            Optional<TurnServices> services,
            AgentApiMapper apiMapper,
            AgentSessionApiService sessionApi,
            Function<String, String> redactor) {
        this.streamTimeout = Objects.requireNonNull(streamTimeout, "streamTimeout");
        if (streamTimeout.isZero() || streamTimeout.isNegative())
            throw new IllegalArgumentException("streamTimeout must be positive");
        this.services = Objects.requireNonNull(services, "services");
        this.apiMapper = Objects.requireNonNull(apiMapper, "apiMapper");
        this.sessionApi = Objects.requireNonNull(sessionApi, "sessionApi");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的对话响应结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ChatApi.ChatResponse chat(ExecutionIdentity identity, ChatRequest request) {
        ChatCommand validated = AgentRequestValidator.validateChat(request);
        AtomicReference<String> observedTurnId = new AtomicReference<>();

        try {
            long startedAt = System.nanoTime();
            AgentRuntimeEvent reply =
                    requireReady()
                            .start(identity, validated)
                            .doOnNext(
                                    event -> {
                                        if (event.getTurnId() != null) {
                                            observedTurnId.compareAndSet(null, event.getTurnId());
                                        }
                                    })
                            .filter(AgentTurnCoordinator::replyOutcome)
                            .next()
                            .block(streamTimeout.plusSeconds(5));
            if (reply == null)
                throw new ApiException(HttpStatus.BAD_GATEWAY, "执行结束但没有返回最终结果，请查询会话状态。");
            requireCompletedReply(reply);
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            return new ChatResponse(
                    validated.getSessionId(), apiMapper.formatReply(reply.getText()), latencyMs);
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        } catch (ApiException error) {
            throw error;
        } catch (SessionTurnBusyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "当前会话已有一轮正在执行");
        } catch (Exception error) {
            Throwable root = NestedExceptionUtils.getMostSpecificCause(error);
            log.error(
                    "Chat execution failed [sessionId={}, requestId={}, turnId={}, category={}, detail={}, frames={}]",
                    validated.getSessionId(),
                    validated.getRequestId(),
                    observedTurnId.get(),
                    root.getClass().getSimpleName(),
                    redactor.apply(root.getMessage() == null ? "" : root.getMessage()),
                    Arrays.stream(root.getStackTrace()).limit(20).toList());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "执行失败，请稍后重试");
        }
    }

    /**
     * 产生执行流并返回对话。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<ChatApi.ChatStreamEvent> streamChat(
            ExecutionIdentity identity, ChatRequest request) {
        ChatCommand validated = AgentRequestValidator.validateChat(request);
        return requireReady()
                .start(identity, validated)
                .filter(AgentApiMapper::userVisible)
                .map(apiMapper::streamEvent)
                .onErrorMap(ApplicationError.class, AgentApiMapper::apiError);
    }

    /** 向可信适配器提供完整执行证据，浏览器展示仍经过过滤。 */
    public Flux<AgentRuntimeEvent> executionEvents(
            ExecutionIdentity identity, ChatRequest request) {
        return requireReady().start(identity, AgentRequestValidator.validateChat(request));
    }

    /**
     * 检查executionSettled对应的条件，供调用方选择后续处理分支。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean executionSettled(ExecutionIdentity identity, String sessionId) {
        return requireReady().settled(identity, sessionId);
    }

    /**
     * 恢复提问用户执行。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前Agent执行协调器持有的执行对象，供相应处理步骤使用。
     * @param ask 当前Agent执行协调器持有的提问对象，供相应处理步骤使用。
     * @param answersJson 回答集合的 JSON 表示，供持久化或协议转换使用。
     */
    public void resumeAskUserTurn(
            ExecutionIdentity identity, AgentTurn turn, AskUserRequest ask, String answersJson) {
        requireReady().resumeAskUser(identity, turn, ask, answersJson);
    }

    /**
     * 恢复批准执行。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前Agent执行协调器持有的执行对象，供相应处理步骤使用。
     * @param resolutions 决定结果集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param remaining 剩余的时间配置，供等待、调度或失效判断使用。
     */
    public void resumeApprovedTurn(
            ExecutionIdentity identity,
            AgentTurn turn,
            List<ApprovalResolution> resolutions,
            Duration remaining) {
        requireReady().resumeApproved(identity, turn, resolutions, remaining);
    }

    /**
     * 订阅会话。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<ChatApi.ChatStreamEvent> subscribeSession(
            ExecutionIdentity identity, SessionSubscribeRequest request) {
        return replayExecutionEvents(identity, request)
                .filter(AgentApiMapper::userVisible)
                .map(apiMapper::streamEvent)
                .onErrorResume(
                        RedisTurnEventBridge.EventLogUnavailableException.class,
                        error ->
                                Flux.just(
                                        apiMapper.sessionError("实时输出暂时无法恢复，当前任务状态尚未确认。正在查询执行状态。")))
                .onErrorResume(
                        TurnExecutionManager.TurnChangedException.class,
                        error -> Flux.just(apiMapper.sessionError("当前 Turn 已变化，请重新查询 Session")))
                .onErrorResume(
                        TurnExecutionManager.TurnNotAvailableException.class,
                        error -> Flux.just(apiMapper.sessionError("当前实例没有可重连的 Turn 事件流")));
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentTurnCoordinator处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<AgentRuntimeEvent> replayExecutionEvents(
            ExecutionIdentity identity, SessionSubscribeRequest request) {
        TurnServices turns = requireReady();
        String sessionId =
                AgentRequestValidator.sessionId(request == null ? null : request.getSessionId());
        String turnId =
                AgentRequestValidator.turnId(request == null ? null : request.getExpectedTurnId());
        try {
            return turns.replay(
                            identity,
                            sessionId,
                            turnId,
                            request.getAfterEventSequence() == null
                                    ? 0L
                                    : Math.max(0L, request.getAfterEventSequence()),
                            request.getAfterTimelineSequence() == null
                                    ? 0L
                                    : Math.max(0L, request.getAfterTimelineSequence()))
                    .onErrorMap(ApplicationError.class, AgentApiMapper::apiError);
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
    }

    /**
     * 取消会话。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话执行响应结果。
     */
    public SessionApi.SessionExecutionResponse cancelSession(
            ExecutionIdentity identity, SessionCancelRequest request) {
        TurnServices turns = requireReady();
        String sessionId =
                AgentRequestValidator.sessionId(request == null ? null : request.getSessionId());
        String turnId =
                AgentRequestValidator.turnId(request == null ? null : request.getExpectedTurnId());
        try {
            turns.cancel(identity, sessionId, turnId);
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
        return sessionApi.execution(identity, sessionId);
    }

    /**
     * 取得并校验就绪。
     *
     * @return 本次操作返回的执行服务集合结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private TurnServices requireReady() {
        if (closed.get()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "执行服务正在关闭");
        return services.orElseThrow(
                () -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 尚未配置，请配置后重启应用"));
    }

    /**
     * 检查replyOutcome对应的条件，供调用方选择后续处理分支。
     *
     * @param event 当前Agent执行协调器持有的事件对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean replyOutcome(AgentRuntimeEvent event) {
        return switch (event.getType()) {
            case TURN_COMPLETED,
                    TURN_FAILED,
                    TURN_TIMED_OUT,
                    TURN_CANCELLED,
                    APPROVAL_REQUIRED,
                    ASK_USER_REQUIRED ->
                    true;
            case EXECUTION_NOTICE ->
                    event.getDetails() instanceof Map<?, ?> details
                            && "EVENT_PERSISTENCE_FAILED".equals(details.get("errorCode"));
            default -> false;
        };
    }

    /**
     * 取得并校验完成回复。
     *
     * @param reply 本次执行返回的文本回复，供宿主消息接口输出。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requireCompletedReply(AgentRuntimeEvent reply) {
        switch (reply.getType()) {
            case TURN_COMPLETED -> {}
            case TURN_TIMED_OUT ->
                    throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "本轮执行超时，请查询会话状态。");
            case TURN_FAILED -> throw new ApiException(HttpStatus.BAD_GATEWAY, "本轮执行失败，请查询会话状态。");
            case TURN_CANCELLED -> throw new ApiException(HttpStatus.CONFLICT, "本轮执行已取消。");
            case APPROVAL_REQUIRED ->
                    throw new ApiException(HttpStatus.CONFLICT, "本轮执行等待工具审批，请查询会话并处理审批。");
            case ASK_USER_REQUIRED ->
                    throw new ApiException(HttpStatus.CONFLICT, "本轮执行等待补充信息，请查询会话并回答问题。");
            case EXECUTION_NOTICE ->
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "结果保存或通知出现异常，请查询会话确认结果。");
            default -> throw new ApiException(HttpStatus.BAD_GATEWAY, "未收到最终执行结果。");
        }
    }

    /**
     * 检查userVisibleEvent对应的条件，供调用方选择后续处理分支。
     *
     * @param event 当前Agent执行协调器持有的事件对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    static boolean userVisibleEvent(AgentRuntimeEvent event) {
        return AgentApiMapper.userVisible(event);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentTurnCoordinator处理步骤使用。
     *
     * @return 本次操作返回的状态结果。
     */
    public InstanceLeaseRenewer.Status leaseRenewalStatus() {
        return services.map(TurnServices::leaseStatus).orElseGet(InstanceLeaseRenewer.Status::new);
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public void close() {
        closed.set(true);
    }
}
