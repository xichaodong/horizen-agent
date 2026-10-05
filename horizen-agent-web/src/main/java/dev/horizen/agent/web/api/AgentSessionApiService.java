package dev.horizen.agent.web.api;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.session.ConversationHistory;
import dev.horizen.agent.application.session.ConversationHistoryQueryService;
import dev.horizen.agent.application.session.SessionApplicationService;
import dev.horizen.agent.application.session.SessionPage;
import dev.horizen.agent.domain.presentation.PresentationStore;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.MessageRole;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.execution.turn.TurnTimelineStore;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.SessionExecutionState;
import dev.horizen.agent.web.api.artifact.ArtifactApi;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.bootstrap.runtime.ArtifactSupport;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Session 查询和目录 API 适配器；活跃 Turn 命令仍由执行协调器处理。 */
public final class AgentSessionApiService {
    /** 执行 Agent 模型与工具循环的运行时接口。 */
    private final AgentRuntime runtime;

    /** 会话对象或会话索引，按相应的归属键定位数据。 */
    private final SessionTurnStore sessions;

    /** 会话目录、标题与生命周期的应用用例。 */
    private final SessionApplicationService sessionUseCases;

    /** 组合正式消息、过程事件与呈现块的历史查询用例。 */
    private final ConversationHistoryQueryService historyQueries;

    /** 共享执行增量与回放的事件通道。 */
    private final RedisTurnEventBridge distributedEvents;

    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final AgentApiMapper mapper;

    /**
     * 创建Agent会话API服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
     * @param presentations 需要持久化或展示的结构化呈现块集合。
     * @param timeline 提供时间线能力的依赖，具体实现由当前组件的组装方传入。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param distributedEvents 当前Agent会话API服务持有的分布式事件集合对象，供相应处理步骤使用。
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    public AgentSessionApiService(
            AgentRuntime runtime,
            SessionTurnStore sessions,
            PresentationStore presentations,
            TurnTimelineStore timeline,
            ArtifactSupport artifacts,
            RedisTurnEventBridge distributedEvents,
            AgentApiMapper mapper) {
        this.runtime = runtime;
        this.sessions = sessions;
        this.sessionUseCases = sessions == null ? null : new SessionApplicationService(sessions);
        this.historyQueries =
                sessions == null
                        ? null
                        : new ConversationHistoryQueryService(
                                sessions,
                                presentations,
                                timeline,
                                artifacts == null ? null : artifacts.getArtifacts());
        this.distributedEvents = distributedEvents;
        this.mapper = mapper;
    }

    /**
     * 创建Agent会话API服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
     * @param presentations 需要持久化或展示的结构化呈现块集合。
     * @param timeline 提供时间线能力的依赖，具体实现由当前组件的组装方传入。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param distributedEvents 当前Agent会话API服务持有的分布式事件集合对象，供相应处理步骤使用。
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param sessionUseCases 提供会话使用用例集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param historyQueries 提供历史Queries能力的依赖，具体实现由当前组件的组装方传入。
     */
    public AgentSessionApiService(
            AgentRuntime runtime,
            SessionTurnStore sessions,
            PresentationStore presentations,
            TurnTimelineStore timeline,
            ArtifactSupport artifacts,
            RedisTurnEventBridge distributedEvents,
            AgentApiMapper mapper,
            SessionApplicationService sessionUseCases,
            ConversationHistoryQueryService historyQueries) {
        this.runtime = runtime;
        this.sessions = sessions;
        this.sessionUseCases = sessionUseCases;
        this.historyQueries = historyQueries;
        this.distributedEvents = distributedEvents;
        this.mapper = mapper;
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentSessionApiService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param rawSessionId 原始会话的标识，用于关联相应记录或执行。
     * @return 本次操作返回的会话执行响应结果。
     */
    public SessionApi.SessionExecutionResponse execution(
            ExecutionIdentity identity, String rawSessionId) {
        requireReady();
        String sessionId = AgentRequestValidator.sessionId(rawSessionId);
        if (sessions != null) {
            return mapper.sessionExecution(
                    sessionUseCases.execution(identity.getOwnerKey(), sessionId));
        }
        SessionExecutionState state =
                runtime.sessionExecution(identity.getOwnerKey(), sessionId).orElse(null);
        return state == null
                ? new SessionApi.SessionExecutionResponse(sessionId, null, "idle", null, null, null)
                : new SessionApi.SessionExecutionResponse(
                        sessionId,
                        state.getTurnId(),
                        state.getStatus().name().toLowerCase(Locale.ROOT),
                        state.getStartedAt(),
                        state.getFinishedAt(),
                        state.getFailureCode());
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentSessionApiService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param rawSessionId 原始会话的标识，用于关联相应记录或执行。
     * @return 本次处理得到的结果集合。
     */
    List<Map<String, Object>> subtasks(ExecutionIdentity identity, String rawSessionId) {
        String sessionId = AgentRequestValidator.sessionId(rawSessionId);
        return runtime instanceof HarnessAgentRuntime harness
                ? harness.listSubtasks(identity.getOwnerKey(), sessionId)
                : List.of();
    }

    /**
     * 取消子任务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param rawSessionId 原始会话的标识，用于关联相应记录或执行。
     * @param rawTaskId 原始任务的标识，用于关联相应记录或执行。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean cancelSubtask(ExecutionIdentity identity, String rawSessionId, String rawTaskId) {
        String sessionId = AgentRequestValidator.sessionId(rawSessionId);
        String taskId = AgentRequestValidator.taskId(rawTaskId);
        return runtime instanceof HarnessAgentRuntime harness
                && harness.cancelSubtask(identity.getOwnerKey(), sessionId, taskId);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param rawSessionId 原始会话的标识，用于关联相应记录或执行。
     * @return 本次操作返回的会话消息集合响应结果。
     */
    SessionApi.SessionMessagesResponse messages(ExecutionIdentity identity, String rawSessionId) {
        requireReady();
        String sessionId = AgentRequestValidator.sessionId(rawSessionId);
        if (sessions == null) {
            return new SessionApi.SessionMessagesResponse(sessionId, List.of(), List.of());
        }
        ConversationHistory history = historyQueries.load(identity.getOwnerKey(), sessionId);
        Map<String, List<ArtifactApi.ArtifactResponse>> inputArtifacts = new LinkedHashMap<>();
        history.getInputArtifactsByTurn()
                .forEach(
                        (turnId, values) ->
                                inputArtifacts.put(
                                        turnId, values.stream().map(mapper::artifact).toList()));
        List<SessionApi.ConversationMessageResponse> messages =
                history.getMessages().stream()
                        .map(
                                message ->
                                        new SessionApi.ConversationMessageResponse(
                                                message.getMessageId(),
                                                message.getTurnId(),
                                                message.getRole().name().toLowerCase(Locale.ROOT),
                                                displayContent(
                                                        message,
                                                        inputArtifacts.get(message.getTurnId())),
                                                message.getSequence(),
                                                message.getCreatedAt(),
                                                message.getRole() == MessageRole.USER
                                                        ? inputArtifacts.getOrDefault(
                                                                message.getTurnId(), List.of())
                                                        : List.of()))
                        .toList();
        List<SessionApi.PresentationResponse> presentations =
                history.getPresentations().stream().map(mapper::presentation).toList();
        List<SessionApi.TimelineEventResponse> timeline =
                new ArrayList<>(
                        history.getTimeline().stream()
                                .map(
                                        value ->
                                                new SessionApi.TimelineEventResponse(
                                                        value.getSequence(),
                                                        value.getTurnId(),
                                                        mapper.timelineEvent(
                                                                value.getPayloadJson())))
                                .toList());
        AgentTurn latest = history.getLatestTurn();
        if (latest != null
                && (latest.getStatus() == TurnStatus.FAILED
                        || latest.getStatus() == TurnStatus.TIMED_OUT
                        || latest.getStatus() == TurnStatus.CANCELLED)
                && timeline.stream()
                        .noneMatch(
                                item ->
                                        latest.getTurnId().equals(item.getTurnId())
                                                && Set.of("error", "cancelled")
                                                        .contains(item.getEvent().getType())
                                                && item.getEvent().getSource() == null)) {
            var type =
                    latest.getStatus() == TurnStatus.CANCELLED
                            ? AgentRuntimeEvent.Type.TURN_CANCELLED
                            : latest.getStatus() == TurnStatus.TIMED_OUT
                                    ? AgentRuntimeEvent.Type.TURN_TIMED_OUT
                                    : AgentRuntimeEvent.Type.TURN_FAILED;
            var fallback =
                    new AgentRuntimeEvent(
                            type,
                            latest.getTurnId(),
                            sessionId,
                            latest.getTurnId(),
                            "执行异常",
                            latest.getStatus() == TurnStatus.CANCELLED ? "本轮执行已取消。" : null,
                            latest.getStatus().name().toLowerCase(Locale.ROOT),
                            null,
                            Map.of(
                                    "errorCode",
                                    latest.getFailureCode() == null
                                            ? "EXECUTION_ERROR"
                                            : latest.getFailureCode()),
                            null,
                            null);
            // 合成事实：零值不会推进持久化时间线游标。
            timeline.add(
                    new SessionApi.TimelineEventResponse(
                            0L, latest.getTurnId(), mapper.streamEvent(fallback)));
        }
        SessionApi.CurrentTurnRecoveryResponse recovery =
                activeRecovery(identity, history.getLatestTurn(), timeline);
        return new SessionApi.SessionMessagesResponse(
                sessionId, messages, presentations, timeline, recovery);
    }

    /**
     * 查询列表中的Agent会话API服务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话列表响应结果。
     */
    SessionApi.SessionListResponse list(
            ExecutionIdentity identity, SessionApi.SessionListRequest request) {
        requireReady();
        if (sessionUseCases == null)
            return new SessionApi.SessionListResponse(List.of(), false, null);
        int limit = request == null || request.getLimit() == null ? 20 : request.getLimit();
        int offset =
                AgentRequestValidator.sessionCursor(request == null ? null : request.getCursor());
        try {
            SessionPage page = sessionUseCases.list(identity.getOwnerKey(), limit, offset);
            return new SessionApi.SessionListResponse(
                    page.getSessions().stream().map(mapper::sessionSummary).toList(),
                    page.isHasMore(),
                    page.isHasMore() ? Integer.toString(page.getNextOffset()) : null);
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentSessionApiService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话摘要响应结果。
     */
    SessionApi.SessionSummaryResponse rename(
            ExecutionIdentity identity, SessionApi.SessionRenameRequest request) {
        requireDistributed();
        String sessionId =
                AgentRequestValidator.sessionId(request == null ? null : request.getSessionId());
        try {
            return mapper.sessionSummary(
                    sessionUseCases.rename(
                            identity.getOwnerKey(),
                            sessionId,
                            request == null ? null : request.getTitle()));
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentSessionApiService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话摘要响应结果。
     */
    SessionApi.SessionSummaryResponse pin(
            ExecutionIdentity identity, SessionApi.SessionPinRequest request) {
        requireDistributed();
        String sessionId =
                AgentRequestValidator.sessionId(request == null ? null : request.getSessionId());
        try {
            return mapper.sessionSummary(
                    sessionUseCases.setPinned(
                            identity.getOwnerKey(),
                            sessionId,
                            request != null && request.isPinned()));
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
    }

    /**
     * 删除Agent会话API服务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     */
    void delete(ExecutionIdentity identity, SessionApi.SessionDeleteRequest request) {
        requireDistributed();
        String sessionId =
                AgentRequestValidator.sessionId(request == null ? null : request.getSessionId());
        try {
            sessionUseCases.archive(identity.getOwnerKey(), sessionId);
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param latest 当前Agent会话API服务持有的最近版本对象，供相应处理步骤使用。
     * @param timeline 时间线的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次操作返回的当前执行恢复响应结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private SessionApi.CurrentTurnRecoveryResponse activeRecovery(
            ExecutionIdentity identity,
            AgentTurn latest,
            List<SessionApi.TimelineEventResponse> timeline) {
        if (latest == null
                || latest.getStatus() != TurnStatus.RUNNING
                || distributedEvents == null) {
            return null;
        }
        RedisTurnEventBridge.EventSnapshot snapshot =
                distributedEvents.snapshot(identity.getOwnerKey(), latest.getTurnId());
        if (!snapshot.isPresent()
                && latest.getStartedAt() != null
                && Duration.between(latest.getStartedAt(), Instant.now())
                                .compareTo(Duration.ofSeconds(1))
                        > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "当前 Turn 的实时增量已不可恢复，请等待终态或重新执行");
        }
        return new SessionApi.CurrentTurnRecoveryResponse(
                latest.getTurnId(),
                ToolHistoryRecovery.uncovered(snapshot, timeline, mapper).stream()
                        .map(mapper::streamEvent)
                        .toList(),
                snapshot.getLastSequence());
    }

    /**
     * 生成当前操作所需的displayContent文本，供调用方继续处理。
     *
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @param attachments 本次消息附带的输入资源，内容解析由运行时适配器完成。
     * @return 本次处理生成或读取的文本。
     */
    private static String displayContent(
            ConversationMessage message, List<ArtifactApi.ArtifactResponse> attachments) {
        if (message.getRole() != MessageRole.USER || attachments == null || attachments.isEmpty()) {
            return message.getContent();
        }
        String marker = "\n\n本轮可用 Artifact：";
        int position = message.getContent().lastIndexOf(marker);
        return position < 0 ? message.getContent() : message.getContent().substring(0, position);
    }

    /**
     * 取得并校验就绪。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireReady() {
        if (runtime == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ARK_API_KEY 未配置，请配置后重启应用");
        }
    }

    /**
     * 取得并校验分布式。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireDistributed() {
        requireReady();
        if (sessions == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Session 管理需要 distributed 存储模式");
        }
    }
}
