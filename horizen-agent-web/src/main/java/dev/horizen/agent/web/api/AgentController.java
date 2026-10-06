package dev.horizen.agent.web.api;

import dev.horizen.agent.application.turn.InstanceLeaseRenewer;
import dev.horizen.agent.web.api.artifact.ArtifactApi.ArtifactDownloadRequest;
import dev.horizen.agent.web.api.artifact.ArtifactApi.ArtifactDownloadResponse;
import dev.horizen.agent.web.api.artifact.ArtifactApi.ArtifactResponse;
import dev.horizen.agent.web.api.chat.ChatApi.ChatRequest;
import dev.horizen.agent.web.api.chat.ChatApi.ChatResponse;
import dev.horizen.agent.web.api.chat.ChatApi.ChatStreamEvent;
import dev.horizen.agent.web.api.interaction.InteractionApi.ApprovalDecisionRequest;
import dev.horizen.agent.web.api.interaction.InteractionApi.ApprovalQueryRequest;
import dev.horizen.agent.web.api.interaction.InteractionApi.ApprovalsResponse;
import dev.horizen.agent.web.api.interaction.InteractionApi.AskUserAnswerRequest;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.api.session.SessionApi.SessionCancelRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionDeleteRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionExecutionResponse;
import dev.horizen.agent.web.api.session.SessionApi.SessionListRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionListResponse;
import dev.horizen.agent.web.api.session.SessionApi.SessionMessagesRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionMessagesResponse;
import dev.horizen.agent.web.api.session.SessionApi.SessionPinRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionQueryRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionRenameRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionSubscribeRequest;
import dev.horizen.agent.web.api.session.SessionApi.SessionSummaryResponse;
import dev.horizen.agent.web.api.status.StatusApi.AgentStatus;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.SseProperties;
import dev.horizen.agent.web.identity.ExecutionIdentityResolver;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;
import dev.horizen.agent.web.stream.SseConnectionManager;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

/**
 * 本地 HTTP 接口入口；解析可信宿主身份，把会话、交互与执行观察请求交给应用门面。
 */
@RestController
@RequestMapping("/api")
public class AgentController {
    /**
     * 本地 Agent HTTP 应用门面，承接查询、执行与交互操作。
     */
    private final AgentService agentService;

    /**
     * 从可信 HTTP 宿主上下文取得不透明执行身份的解析器。
     */
    private final ExecutionIdentityResolver identityResolver;

    /**
     * 本实例 SSE 观察连接的有界队列、发送与关闭管理器。
     */
    private final SseConnectionManager sseConnections;

    /**
     * 创建Agent接口控制器，初始化该组件所需的状态、配置或依赖。
     *
     * @param agentService     提供Agent服务能力的依赖，具体实现由当前组件的组装方传入。
     * @param identityResolver 提供身份解析器能力的依赖，具体实现由当前组件的组装方传入。
     */
    public AgentController(AgentService agentService, ExecutionIdentityResolver identityResolver) {
        this(agentService, identityResolver, new SseProperties());
    }

    /**
     * 创建Agent接口控制器，初始化该组件所需的状态、配置或依赖。
     *
     * @param agentService     提供Agent服务能力的依赖，具体实现由当前组件的组装方传入。
     * @param identityResolver 提供身份解析器能力的依赖，具体实现由当前组件的组装方传入。
     * @param sseProperties    当前Agent接口控制器持有的SSE配置对象，供相应处理步骤使用。
     */
    public AgentController(
            AgentService agentService,
            ExecutionIdentityResolver identityResolver,
            SseProperties sseProperties) {
        this.agentService = agentService;
        this.identityResolver = identityResolver;
        this.sseConnections = new SseConnectionManager(sseProperties);
    }

    /**
     * 创建Agent接口控制器，初始化该组件所需的状态、配置或依赖。
     *
     * @param service     提供服务能力的依赖，具体实现由当前组件的组装方传入。
     * @param identity    可信宿主解析的执行身份，供访问范围与审计使用。
     * @param connections 当前Agent接口控制器持有的连接对象，供相应处理步骤使用。
     */
    @Autowired
    public AgentController(
            AgentService service,
            ExecutionIdentityResolver identity,
            SseConnectionManager connections) {
        this.agentService = service;
        this.identityResolver = identity;
        this.sseConnections = connections;
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentController处理步骤使用。
     *
     * @return 本次操作返回的Agent状态结果。
     */
    @GetMapping("/status")
    public AgentStatus status() {
        return agentService.status();
    }

    /**
     * 读取本实例的 SSE、租约、存储连接池与 JVM 资源快照。
     *
     * @return 本次操作返回的事件流运行时状态结果。
     */
    @GetMapping("/stream/status")
    public StreamRuntimeStatus streamStatus() {
        SseConnectionManager.Snapshot value = sseConnections.snapshot();
        return new StreamRuntimeStatus(
                value.getActiveConnections(),
                value.getTotalConnections(),
                value.getRejectedConnections(),
                value.getQueuedEvents(),
                value.getQueuedBytes(),
                value.getPeakQueuedBytes(),
                value.getWriterActiveThreads(),
                value.getWriterPoolSize(),
                value.getWriterQueueSize(),
                value.getNormalCloses(),
                value.getErrorCloses(),
                value.getSlowClientCloses(),
                value.getTimeoutCloses(),
                value.getDisconnectedCloses(),
                agentService.streamEventStatus(),
                agentService.leaseRenewalStatus(),
                agentService.databasePoolStatus(),
                agentService.redisPoolsStatus(),
                processResourceStatus());
    }

    /**
     * 从当前 JVM 读取堆内存、存活线程与可用处理器统计。
     *
     * @return 本次操作返回的进程资源状态结果。
     */
    private static ProcessResourceStatus processResourceStatus() {
        Runtime runtime = Runtime.getRuntime();
        return new ProcessResourceStatus(
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.totalMemory(),
                runtime.maxMemory(),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                runtime.availableProcessors());
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentController处理步骤使用。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的会话执行响应结果。
     */
    @PostMapping("/session/query")
    public SessionExecutionResponse sessionExecution(
            @RequestBody SessionQueryRequest request, HttpServletRequest httpRequest) {
        return agentService.sessionExecution(
                identityResolver.resolve(httpRequest), request.getSessionId());
    }

    /**
     * 订阅会话。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的SSE发送器结果。
     */
    @PostMapping(value = "/session/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeSession(
            @RequestBody SessionSubscribeRequest request, HttpServletRequest httpRequest) {
        return stream(
                agentService.subscribeSession(identityResolver.resolve(httpRequest), request));
    }

    /**
     * 取消会话。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的会话执行响应结果。
     */
    @PostMapping("/session/cancel")
    public SessionExecutionResponse cancelSession(
            @RequestBody SessionCancelRequest request, HttpServletRequest httpRequest) {
        return agentService.cancelSession(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentController处理步骤使用。
     *
     * @param sessionId   会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次处理得到的结果集合。
     */
    @GetMapping("/session/subtasks")
    public List<Map<String, Object>> subtasks(
            @RequestParam String sessionId, HttpServletRequest httpRequest) {
        return agentService.subtasks(identityResolver.resolve(httpRequest), sessionId);
    }

    /**
     * 取消子任务。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 按返回类型约定组织的结果映射。
     */
    @PostMapping("/session/subtasks/cancel")
    public Map<String, Object> cancelSubtask(
            @RequestBody SubtaskCancelRequest request, HttpServletRequest httpRequest) {
        boolean cancelled =
                agentService.cancelSubtask(
                        identityResolver.resolve(httpRequest),
                        request.getSessionId(),
                        request.getTaskId());
        return Map.of("cancelled", cancelled);
    }

    /**
     * 子任务取消的接口请求，承载调用方提交的定位信息与输入。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubtaskCancelRequest {
        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        private String sessionId;

        /**
         * 任务的标识，用于关联相应记录或执行。
         */
        private String taskId;
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentController处理步骤使用。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的会话消息集合响应结果。
     */
    @PostMapping("/session/messages/query")
    public SessionMessagesResponse sessionMessages(
            @RequestBody SessionMessagesRequest request, HttpServletRequest httpRequest) {
        return agentService.sessionMessages(
                identityResolver.resolve(httpRequest), request.getSessionId());
    }

    /**
     * 查询列表中的会话集合。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的会话列表响应结果。
     */
    @PostMapping("/sessions/query")
    public SessionListResponse listSessions(
            @RequestBody(required = false) SessionListRequest request,
            HttpServletRequest httpRequest) {
        return agentService.listSessions(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentController处理步骤使用。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的会话摘要响应结果。
     */
    @PostMapping("/session/rename")
    public SessionSummaryResponse renameSession(
            @RequestBody SessionRenameRequest request, HttpServletRequest httpRequest) {
        return agentService.renameSession(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 设置会话置顶。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的会话摘要响应结果。
     */
    @PostMapping("/session/pin")
    public SessionSummaryResponse setSessionPinned(
            @RequestBody SessionPinRequest request, HttpServletRequest httpRequest) {
        return agentService.setSessionPinned(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 删除会话。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 按返回类型约定组织的结果映射。
     */
    @PostMapping("/session/delete")
    public Map<String, Object> deleteSession(
            @RequestBody SessionDeleteRequest request, HttpServletRequest httpRequest) {
        agentService.deleteSession(identityResolver.resolve(httpRequest), request);
        return Map.of("deleted", true);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentController处理步骤使用。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的审批集合响应结果。
     */
    @PostMapping("/session/approvals/query")
    public ApprovalsResponse pendingApprovals(
            @RequestBody ApprovalQueryRequest request, HttpServletRequest httpRequest) {
        return agentService.pendingApprovals(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 提交决定并处理审批。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的会话执行响应结果。
     */
    @PostMapping("/session/approval/decide")
    public SessionExecutionResponse decideApproval(
            @RequestBody ApprovalDecisionRequest request, HttpServletRequest httpRequest) {
        return agentService.decideApproval(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentController处理步骤使用。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的对话响应结果。
     */
    @PostMapping("/chat")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        return agentService.chat(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 产生执行流并返回对话。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的SSE发送器结果。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        return stream(agentService.streamChat(identityResolver.resolve(httpRequest), request));
    }

    /**
     * 上传产物。
     *
     * @param file        当前Agent接口控制器持有的文件对象，供相应处理步骤使用。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的产物响应结果。
     */
    @PostMapping(value = "/artifacts/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ArtifactResponse uploadArtifact(
            @RequestParam("file") MultipartFile file, HttpServletRequest httpRequest) {
        return agentService.uploadArtifact(identityResolver.resolve(httpRequest), file);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentController处理步骤使用。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的产物下载响应结果。
     */
    @PostMapping("/artifacts/download-url")
    public ArtifactDownloadResponse artifactDownloadUrl(
            @RequestBody ArtifactDownloadRequest request, HttpServletRequest httpRequest) {
        return agentService.artifactDownloadUrl(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 提交回答并处理提问用户。
     *
     * @param request     当前操作的请求参数。
     * @param httpRequest 当前Agent接口控制器持有的HTTP请求对象，供相应处理步骤使用。
     * @return 本次操作返回的会话执行响应结果。
     */
    @PostMapping("/ask-user/answer")
    public SessionApi.SessionExecutionResponse answerAskUser(
            @RequestBody AskUserAnswerRequest request, HttpServletRequest httpRequest) {
        return agentService.answerAskUser(identityResolver.resolve(httpRequest), request);
    }

    /**
     * 将宿主事件流接入有界 SSE 观察连接，浏览器断开不会直接取消实际执行。
     *
     * @param events 当前执行或历史事件集合，供持久化、回放与观测使用。
     * @return 本次操作返回的SSE发送器结果。
     */
    private SseEmitter stream(Flux<ChatStreamEvent> events) {
        return stream(
                events, new SseEmitter(agentService.streamTimeout().plusSeconds(5).toMillis()));
    }

    /**
     * 包内可见的测试入口，用于在不建立真实 Socket 连接时模拟慢客户端。
     */
    SseEmitter stream(Flux<ChatStreamEvent> events, SseEmitter emitter) {
        return sseConnections.open(events, emitter);
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     */
    @PreDestroy
    public void close() {
        sseConnections.close();
    }

    /**
     * 本实例 SSE、事件回放、租约及存储连接池的运行资源快照。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamRuntimeStatus {
        /**
         * 当前仍处于观察状态的 SSE 连接数。
         */
        private int activeConnections;

        /**
         * 自本实例启动以来接收的 SSE 连接总数。
         */
        private long totalConnections;

        /**
         * 因连接容量或准入条件被拒绝的 SSE 连接累计次数。
         */
        private long rejectedConnections;

        /**
         * 当前 SSE 连接队列中等待发送的事件数量。
         */
        private int queuedEvents;

        /**
         * 当前 SSE 连接队列中等待发送的内容字节数。
         */
        private long queuedBytes;

        /**
         * 观察期间 SSE 待发送内容字节数的峰值。
         */
        private long peakQueuedBytes;

        /**
         * SSE 写入线程池当前正在执行工作的线程数。
         */
        private int writerActiveThreads;

        /**
         * SSE 写入线程池当前保留的线程数。
         */
        private int writerPoolSize;

        /**
         * SSE 写入线程池尚未开始处理的任务数。
         */
        private int writerQueueSize;

        /**
         * 自本实例启动以来正常结束的 SSE 连接次数。
         */
        private long normalCloses;

        /**
         * 因写入或连接错误而结束的 SSE 连接累计次数。
         */
        private long errorCloses;

        /**
         * 因客户端消费过慢而结束的 SSE 连接累计次数。
         */
        private long slowClientCloses;

        /**
         * 因观察连接时限到期而结束的 SSE 连接累计次数。
         */
        private long timeoutCloses;

        /**
         * 因浏览器或网络断开而结束的 SSE 连接累计次数。
         */
        private long disconnectedCloses;

        /**
         * 当前分布式事件桥的轮询统计。
         */
        private RedisTurnEventBridge.EventBridgeStatus redisPolling;

        /**
         * 当前执行实例的租约续期统计。
         */
        private InstanceLeaseRenewer.Status leaseRenewal;

        /**
         * 当前 JDBC 连接池的使用与等待统计。
         */
        private RuntimeStorage.DatabasePoolStatus databasePool;

        /**
         * 当前 Redis 命令池与订阅池的使用统计。
         */
        private RuntimeStorage.RedisPoolsStatus redisPools;

        /**
         * 当前 JVM 的内存、线程与处理器资源统计。
         */
        private ProcessResourceStatus process;
    }

    /**
     * 当前 JVM 进程的内存、线程与处理器资源快照。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessResourceStatus {
        /**
         * JVM 当前已使用的堆内存字节数。
         */
        private long heapUsedBytes;

        /**
         * JVM 当前已提交的堆内存字节数。
         */
        private long heapCommittedBytes;

        /**
         * JVM 允许使用的最大堆内存字节数。
         */
        private long heapMaxBytes;

        /**
         * JVM 当前存活的线程数量。
         */
        private int liveThreads;

        /**
         * JVM 当前可使用的处理器数量。
         */
        private int availableProcessors;
    }
}
