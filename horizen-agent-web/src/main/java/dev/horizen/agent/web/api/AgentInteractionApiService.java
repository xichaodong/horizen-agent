package dev.horizen.agent.web.api;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.interaction.ApprovalApplicationService;
import dev.horizen.agent.application.interaction.ApprovalChoiceCommand;
import dev.horizen.agent.application.interaction.ApprovalTurnResumer;
import dev.horizen.agent.application.interaction.AskUserAnswerValidator;
import dev.horizen.agent.application.interaction.AskUserApplicationService;
import dev.horizen.agent.application.interaction.AskUserTurnResumer;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.transaction.UnitOfWork;
import dev.horizen.agent.web.api.interaction.InteractionApi;
import dev.horizen.agent.web.api.session.SessionApi;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 审批和 ask_user 应用用例的 HTTP 适配器。
 */
public final class AgentInteractionApiService {
    /**
     * 审批存储或待处理审批集合，用于原执行的暂停与恢复。
     */
    private final ApprovalApplicationService approvals;

    /**
     * 澄清请求的存储或服务，用于回答处理与执行恢复。
     */
    private final AskUserApplicationService askUsers;

    /**
     * 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    private final AgentApiMapper mapper;

    /**
     * 创建Agent交互API服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions        会话对象或会话索引，按相应的归属键定位数据。
     * @param approvalStore   提供审批存储能力的依赖，具体实现由当前组件的组装方传入。
     * @param askUserStore    提供提问用户存储能力的依赖，具体实现由当前组件的组装方传入。
     * @param transactions    执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     * @param executorId      持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseTtl        执行实例租约的有效时长。
     * @param turnTimeout     执行允许持续的最长等待时间。
     * @param mapper          本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param approvalResumer 当前Agent交互API服务持有的审批恢复入口对象，供相应处理步骤使用。
     * @param askUserResumer  当前Agent交互API服务持有的提问用户恢复入口对象，供相应处理步骤使用。
     */
    public AgentInteractionApiService(
            SessionTurnStore sessions,
            ApprovalStore approvalStore,
            AskUserStore askUserStore,
            UnitOfWork transactions,
            String executorId,
            Duration leaseTtl,
            Duration turnTimeout,
            AgentApiMapper mapper,
            ApprovalTurnResumer approvalResumer,
            AskUserTurnResumer askUserResumer) {
        this.mapper = mapper;
        this.approvals =
                sessions == null
                        ? null
                        : new ApprovalApplicationService(
                        sessions,
                        approvalStore,
                        approvalResumer,
                        transactions,
                        executorId,
                        leaseTtl,
                        turnTimeout);
        this.askUsers =
                sessions == null
                        ? null
                        : new AskUserApplicationService(
                        sessions,
                        askUserStore,
                        AskUserAnswerValidator::encode,
                        askUserResumer,
                        transactions,
                        executorId,
                        leaseTtl);
    }

    /**
     * 创建Agent交互API服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param approvals 审批存储或待处理审批集合，用于原执行的暂停与恢复。
     * @param asks      提供提问集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param mapper    本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    public AgentInteractionApiService(
            ApprovalApplicationService approvals,
            AskUserApplicationService asks,
            AgentApiMapper mapper) {
        this.approvals = approvals;
        this.askUsers = asks;
        this.mapper = mapper;
    }

    /**
     * 提交回答并处理Agent交互API服务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request  当前操作的请求参数。
     * @return 本次操作返回的会话执行响应结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    SessionApi.SessionExecutionResponse answer(
            ExecutionIdentity identity, InteractionApi.AskUserAnswerRequest request) {
        requireDistributed();
        if (request == null) throw new ApiException(HttpStatus.BAD_REQUEST, "请求不能为空");
        try {
            return mapper.sessionExecution(
                    askUsers.answer(
                            identity,
                            request.getAskUserId(),
                            request.getAnswers(),
                            request.isSkip()));
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request  当前操作的请求参数。
     * @return 本次操作返回的审批集合响应结果。
     */
    InteractionApi.ApprovalsResponse pending(
            ExecutionIdentity identity, InteractionApi.ApprovalQueryRequest request) {
        requireDistributed();
        String sessionId =
                AgentRequestValidator.sessionId(request == null ? null : request.getSessionId());
        String turnId = AgentRequestValidator.turnId(request == null ? null : request.getTurnId());
        List<InteractionApi.ApprovalResponse> values =
                approvals.pending(identity.getOwnerKey(), sessionId, turnId).stream()
                        .map(
                                value ->
                                        new InteractionApi.ApprovalResponse(
                                                value.getApprovalId(),
                                                value.getTurnId(),
                                                value.getToolCallId(),
                                                value.getToolName(),
                                                mapper.jsonMap(value.getToolArgumentsJson()),
                                                value.getStatus().name().toLowerCase(Locale.ROOT),
                                                value.getExpiresAt(),
                                                mapper.approvalPresentation(value)))
                        .toList();
        return new InteractionApi.ApprovalsResponse(sessionId, turnId, values);
    }

    /**
     * 提交决定并处理Agent交互API服务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request  当前操作的请求参数。
     * @return 本次操作返回的会话执行响应结果。
     */
    SessionApi.SessionExecutionResponse decide(
            ExecutionIdentity identity, InteractionApi.ApprovalDecisionRequest request) {
        requireDistributed();
        String sessionId =
                AgentRequestValidator.sessionId(request == null ? null : request.getSessionId());
        String turnId = AgentRequestValidator.turnId(request == null ? null : request.getTurnId());
        try {
            List<ApprovalChoiceCommand> decisions =
                    request.getDecisions() == null
                            ? List.of()
                            : request.getDecisions().stream()
                            .map(
                                    value ->
                                            new ApprovalChoiceCommand(
                                                    value == null
                                                            ? null
                                                            : value.getApprovalId(),
                                                    value != null && value.isApproved()))
                            .toList();
            return mapper.sessionExecution(
                    approvals.decide(identity, sessionId, turnId, decisions));
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
    }

    /**
     * 取得并校验分布式。
     *
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireDistributed() {
        if (approvals == null || askUsers == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "交互恢复需要 distributed 存储模式");
        }
    }
}
