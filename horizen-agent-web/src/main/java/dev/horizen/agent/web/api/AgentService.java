package dev.horizen.agent.web.api;

import static dev.horizen.agent.web.api.artifact.ArtifactApi.*;
import static dev.horizen.agent.web.api.chat.ChatApi.*;
import static dev.horizen.agent.web.api.interaction.InteractionApi.*;
import static dev.horizen.agent.web.api.session.SessionApi.*;
import static dev.horizen.agent.web.api.status.StatusApi.*;

import dev.horizen.agent.application.turn.InstanceLeaseRenewer;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.web.bootstrap.storage.AgentHostResources;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.execution.AgentTurnCoordinator;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 面向 HTTP 的服务入口；组件组装由 AgentHostConfiguration 完成。 */
@RequiredArgsConstructor
public class AgentService {
    /** 产物上传、引用与下载接口的应用服务。 */
    private final ArtifactApiService artifactApi;

    /** 会话目录、执行状态与历史查询接口的应用服务。 */
    private final AgentSessionApiService sessionApi;

    /** 审批和澄清提交接口的应用服务。 */
    private final AgentInteractionApiService interactionApi;

    /** 启动与恢复执行的应用协调器。 */
    private final AgentTurnCoordinator turnCoordinator;

    /** 把宿主配置与各适配器统计转换为状态响应的服务。 */
    private final AgentStatusService statusService;

    /** 当前宿主依赖的资源状态投影。 */
    private final AgentHostResources resources;

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @return 本次操作返回的Agent状态结果。
     */
    public AgentStatus status() {
        return statusService.status();
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的对话响应结果。
     */
    public ChatResponse chat(ExecutionIdentity identity, ChatRequest request) {
        return turnCoordinator.chat(identity, request);
    }

    /**
     * 产生执行流并返回对话。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<ChatStreamEvent> streamChat(ExecutionIdentity identity, ChatRequest request) {
        return turnCoordinator.streamChat(identity, request);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<AgentRuntimeEvent> executionEvents(
            ExecutionIdentity identity, ChatRequest request) {
        return turnCoordinator.executionEvents(identity, request);
    }

    /**
     * 检查executionSettled对应的条件，供调用方选择后续处理分支。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean executionSettled(ExecutionIdentity identity, String sessionId) {
        return turnCoordinator.executionSettled(identity, sessionId);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<AgentRuntimeEvent> replayExecutionEvents(
            ExecutionIdentity identity, SessionSubscribeRequest request) {
        return turnCoordinator.replayExecutionEvents(identity, request);
    }

    /**
     * 上传产物。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param file 当前Agent服务持有的文件对象，供相应处理步骤使用。
     * @return 本次操作返回的产物响应结果。
     */
    public ArtifactResponse uploadArtifact(ExecutionIdentity identity, MultipartFile file) {
        return artifactApi.upload(identity, file);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的产物下载响应结果。
     */
    public ArtifactDownloadResponse artifactDownloadUrl(
            ExecutionIdentity identity, ArtifactDownloadRequest request) {
        return artifactApi.download(identity, request);
    }

    /**
     * 提交回答并处理提问用户。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话执行响应结果。
     */
    public SessionExecutionResponse answerAskUser(
            ExecutionIdentity identity, AskUserAnswerRequest request) {
        if (interactionApi == null) requireDistributedStorage();
        return interactionApi.answer(identity, request);
    }

    /**
     * 产生执行流并返回超时。
     *
     * @return 本次操作返回的耗时结果。
     */
    public Duration streamTimeout() {
        return turnCoordinator.getStreamTimeout();
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param rawSessionId 原始会话的标识，用于关联相应记录或执行。
     * @return 本次操作返回的会话执行响应结果。
     */
    public SessionExecutionResponse sessionExecution(
            ExecutionIdentity identity, String rawSessionId) {
        return sessionApi.execution(identity, rawSessionId);
    }

    /**
     * 订阅会话。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Flux<ChatStreamEvent> subscribeSession(
            ExecutionIdentity identity, SessionSubscribeRequest request) {
        return turnCoordinator.subscribeSession(identity, request);
    }

    /**
     * 产生执行流并返回事件状态。
     *
     * @return 本次操作返回的事件桥接器状态结果。
     */
    RedisTurnEventBridge.EventBridgeStatus streamEventStatus() {
        return resources.streamEventStatus();
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @return 本次操作返回的数据库连接池状态结果。
     */
    RuntimeStorage.DatabasePoolStatus databasePoolStatus() {
        return resources.databasePoolStatus();
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @return 本次操作返回的Redis连接池集合状态结果。
     */
    RuntimeStorage.RedisPoolsStatus redisPoolsStatus() {
        return resources.redisPoolsStatus();
    }

    /**
     * 取消会话。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话执行响应结果。
     */
    public SessionExecutionResponse cancelSession(
            ExecutionIdentity identity, SessionCancelRequest request) {
        return turnCoordinator.cancelSession(identity, request);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    public List<Map<String, Object>> subtasks(ExecutionIdentity identity, String sessionId) {
        return sessionApi.subtasks(identity, sessionId);
    }

    /**
     * 取消子任务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param taskId 任务的标识，用于关联相应记录或执行。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean cancelSubtask(ExecutionIdentity identity, String sessionId, String taskId) {
        return sessionApi.cancelSubtask(identity, sessionId, taskId);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param rawSessionId 原始会话的标识，用于关联相应记录或执行。
     * @return 本次操作返回的会话消息集合响应结果。
     */
    public SessionMessagesResponse sessionMessages(
            ExecutionIdentity identity, String rawSessionId) {
        return sessionApi.messages(identity, rawSessionId);
    }

    /**
     * 查询列表中的会话集合。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话列表响应结果。
     */
    public SessionListResponse listSessions(
            ExecutionIdentity identity, SessionListRequest request) {
        return sessionApi.list(identity, request);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话摘要响应结果。
     */
    public SessionSummaryResponse renameSession(
            ExecutionIdentity identity, SessionRenameRequest request) {
        return sessionApi.rename(identity, request);
    }

    /**
     * 设置会话置顶。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话摘要响应结果。
     */
    public SessionSummaryResponse setSessionPinned(
            ExecutionIdentity identity, SessionPinRequest request) {
        return sessionApi.pin(identity, request);
    }

    /**
     * 删除会话。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     */
    public void deleteSession(ExecutionIdentity identity, SessionDeleteRequest request) {
        sessionApi.delete(identity, request);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的审批集合响应结果。
     */
    public ApprovalsResponse pendingApprovals(
            ExecutionIdentity identity, ApprovalQueryRequest request) {
        if (interactionApi == null) requireDistributedStorage();
        return interactionApi.pending(identity, request);
    }

    /**
     * 提交决定并处理审批。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的会话执行响应结果。
     */
    public SessionExecutionResponse decideApproval(
            ExecutionIdentity identity, ApprovalDecisionRequest request) {
        if (interactionApi == null) requireDistributedStorage();
        return interactionApi.decide(identity, request);
    }

    /**
     * 取得并校验分布式存储。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireDistributedStorage() {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "交互恢复需要已就绪的 distributed 存储模式");
    }

    /**
     * 检查userVisibleEvent对应的条件，供调用方选择后续处理分支。
     *
     * @param event 当前Agent服务持有的事件对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    static boolean userVisibleEvent(AgentRuntimeEvent event) {
        return AgentApiMapper.userVisible(event);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentService处理步骤使用。
     *
     * @return 本次操作返回的状态结果。
     */
    InstanceLeaseRenewer.Status leaseRenewalStatus() {
        return turnCoordinator.leaseRenewalStatus();
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @PreDestroy
    public void close() {
        turnCoordinator.close();
    }
}
