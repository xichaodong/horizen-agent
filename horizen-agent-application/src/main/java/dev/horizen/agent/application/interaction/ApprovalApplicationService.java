package dev.horizen.agent.application.interaction;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.session.SessionExecutionView;
import dev.horizen.agent.common.validation.Preconditions;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalDecisionCommand;
import dev.horizen.agent.interaction.approval.ApprovalDecisionResult;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.transaction.UnitOfWork;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 审批查询和决策用例。 */
public final class ApprovalApplicationService {
    /** 会话对象或会话索引，按相应的归属键定位数据。 */
    private final SessionTurnStore sessions;

    /** 审批存储或待处理审批集合，用于原执行的暂停与恢复。 */
    private final ApprovalStore approvals;

    /** 在交互事务提交后恢复原执行的入口。 */
    private final ApprovalTurnResumer resumer;

    /** 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。 */
    private final UnitOfWork transactions;

    /** 持有当前执行段的执行实例标识，用于租约与跨实例控制。 */
    private final String executorId;

    /** 执行实例租约的有效时长。 */
    private final Duration leaseTtl;

    /** 默认允许持续的最长等待时间。 */
    private final Duration defaultTimeout;

    /** 时间来源，用于计算更新时间、过期时间或执行时限。 */
    private final Clock clock;

    /**
     * 创建审批应用服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
     * @param approvals 审批存储或待处理审批集合，用于原执行的暂停与恢复。
     * @param resumer 当前审批应用服务持有的恢复入口对象，供相应处理步骤使用。
     * @param transactions 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseTtl 执行实例租约的有效时长。
     * @param defaultTimeout 默认允许持续的最长等待时间。
     */
    public ApprovalApplicationService(
            SessionTurnStore sessions,
            ApprovalStore approvals,
            ApprovalTurnResumer resumer,
            UnitOfWork transactions,
            String executorId,
            Duration leaseTtl,
            Duration defaultTimeout) {
        this(
                sessions,
                approvals,
                resumer,
                transactions,
                executorId,
                leaseTtl,
                defaultTimeout,
                Clock.systemUTC());
    }

    /**
     * 创建审批应用服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
     * @param approvals 审批存储或待处理审批集合，用于原执行的暂停与恢复。
     * @param resumer 当前审批应用服务持有的恢复入口对象，供相应处理步骤使用。
     * @param transactions 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseTtl 执行实例租约的有效时长。
     * @param defaultTimeout 默认允许持续的最长等待时间。
     * @param clock 时间来源，用于计算更新时间、过期时间或执行时限。
     */
    public ApprovalApplicationService(
            SessionTurnStore sessions,
            ApprovalStore approvals,
            ApprovalTurnResumer resumer,
            UnitOfWork transactions,
            String executorId,
            Duration leaseTtl,
            Duration defaultTimeout,
            Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.resumer = Objects.requireNonNull(resumer, "resumer");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.executorId = Preconditions.requireText(executorId, "executorId 不能为空");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 计算或取得本方法声明的结果，供当前ApprovalApplicationService处理步骤使用。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    public List<ApprovalRequest> pending(String ownerKey, String sessionId, String turnId) {
        return approvals.findPending(ownerKey, sessionId, turnId);
    }

    /**
     * 提交一批审批决定；数据库提交后才恢复原执行，避免在短事务中运行模型与工具。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param decisions 同一批待确认操作的决定集合，用于恢复原执行。
     * @return 本次操作返回的会话执行视图结果。
     */
    public SessionExecutionView decide(
            ExecutionIdentity identity,
            String sessionId,
            String turnId,
            List<ApprovalChoiceCommand> decisions) {
        CommittedResume resume =
                transactions.execute(
                        () -> decideInTransaction(identity, sessionId, turnId, decisions));
        // 仅成功提交状态迁移的请求负责调度；构建或执行 Agent 和工具运行时时不能持有数据库事务。
        if (resume != null) {
            resumer.resume(identity, resume.turn, resume.resolutions, resume.remaining);
        }
        return execution(identity.getOwnerKey(), sessionId);
    }

    /**
     * 在同一工作单元中更新审批记录与执行状态，生成提交后可用的恢复信息。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param decisions 同一批待确认操作的决定集合，用于恢复原执行。
     * @return 本次操作返回的已提交恢复执行结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private CommittedResume decideInTransaction(
            ExecutionIdentity identity,
            String sessionId,
            String turnId,
            List<ApprovalChoiceCommand> decisions) {
        AgentTurn turn =
                sessions.findTurn(identity.getOwnerKey(), turnId)
                        .orElseThrow(
                                () ->
                                        new ApplicationError(
                                                ApplicationError.Code.NOT_FOUND, "Turn 不存在"));
        if (!turn.getSessionId().equals(sessionId)) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "Turn 不属于当前 Session");
        }
        if (turn.getStatus() != TurnStatus.WAITING_APPROVAL) {
            return null;
        }
        List<ApprovalRequest> pending =
                approvals.findPending(identity.getOwnerKey(), sessionId, turnId);
        if (pending.isEmpty()) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "当前 Turn 没有待审批工具");
        }
        Map<String, Boolean> choices = choices(decisions);
        if (choices.size() != pending.size()
                || !choices.keySet()
                        .containsAll(
                                pending.stream().map(ApprovalRequest::getApprovalId).toList())) {
            throw new ApplicationError(
                    ApplicationError.Code.INVALID_ARGUMENT, "必须处理当前 Turn 的全部待审批工具");
        }

        Instant now = Instant.now(clock);
        if (turn.getDeadlineAt() != null && !now.isBefore(turn.getDeadlineAt())) {
            sessions.transitionTurn(
                    new TransitionTurnCommand(
                                    identity.getOwnerKey(),
                                    sessionId,
                                    turnId,
                                    TurnStatus.TIMED_OUT,
                                    null,
                                    null,
                                    now,
                                    "APPROVAL_TIMEOUT",
                                    null,
                                    null)
                            .expectVersion(turn.getVersion()));
            return null;
        }
        // 先获取 Session/Turn 状态迁移锁，再锁定审批行。相关 JDBC 仓储均参与此 UnitOfWork，
        // 决策失败会整体回滚。
        TransitionTurnResult transitioned =
                sessions.transitionTurn(
                        new TransitionTurnCommand(
                                        identity.getOwnerKey(),
                                        sessionId,
                                        turnId,
                                        TurnStatus.RUNNING,
                                        executorId,
                                        now.plus(leaseTtl),
                                        now,
                                        null,
                                        null,
                                        null)
                                .expectVersion(turn.getVersion()));
        if (transitioned.getOutcome() != TransitionTurnResult.Outcome.UPDATED) {
            return null;
        }
        List<ApprovalResolution> resolutions =
                pending.stream()
                        .map(
                                value ->
                                        decideOne(
                                                identity,
                                                value,
                                                choices.get(value.getApprovalId()),
                                                now))
                        .toList();
        Duration remaining =
                turn.getDeadlineAt() == null
                        ? defaultTimeout
                        : Duration.between(now, turn.getDeadlineAt());
        return new CommittedResume(transitioned.getTurn(), resolutions, remaining);
    }

    /**
     * 根据原审批的状态、版本和有效期提交一条决定。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param value 待校验、转换或保存的原始值。
     * @param approved 本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
     * @param now 用于本次更新或过期判断的当前时间。
     * @return 本次操作返回的审批Resolution结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private ApprovalResolution decideOne(
            ExecutionIdentity identity, ApprovalRequest value, boolean approved, Instant now) {
        ApprovalDecisionResult result =
                approvals.decide(
                        new ApprovalDecisionCommand(
                                identity.getOwnerKey(),
                                value.getApprovalId(),
                                approved,
                                identity.getActorId(),
                                now));
        ApprovalRequest stored = result == null ? null : result.getApproval();
        if (stored == null
                || (result.getOutcome() != ApprovalDecisionResult.Outcome.UPDATED
                        && result.getOutcome() != ApprovalDecisionResult.Outcome.ALREADY_DECIDED)
                || !value.getApprovalId().equals(stored.getApprovalId())
                || !value.getOwnerKey().equals(stored.getOwnerKey())
                || !value.getSessionId().equals(stored.getSessionId())
                || !value.getTurnId().equals(stored.getTurnId())
                || (stored.getStatus() != ApprovalStatus.APPROVED
                        && stored.getStatus() != ApprovalStatus.DENIED)) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "审批决定未成功保存，不能恢复执行");
        }
        return new ApprovalResolution(stored, stored.getStatus() == ApprovalStatus.APPROVED);
    }

    /** 审批数据库事务提交后可执行的恢复信息，避免在事务中运行 Agent。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class CommittedResume {
        /** 当前操作所关联的原执行事实。 */
        private final AgentTurn turn;

        /** 决定结果集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private final List<ApprovalResolution> resolutions;

        /** 剩余的时间配置，供等待、调度或失效判断使用。 */
        private final Duration remaining;
    }

    /**
     * 把最新执行事实投影为宿主可查询的执行状态。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的会话执行视图结果。
     */
    private SessionExecutionView execution(String ownerKey, String sessionId) {
        AgentTurn latest = sessions.findLatestTurn(ownerKey, sessionId).orElse(null);
        return latest == null
                ? SessionExecutionView.idle(sessionId)
                : new SessionExecutionView(
                        sessionId,
                        latest.getTurnId(),
                        latest.getStatus(),
                        latest.getStartedAt(),
                        latest.getFinishedAt(),
                        latest.getFailureCode());
    }

    /**
     * 把外部提交的决定按审批标识组织，供逐条核对。
     *
     * @param decisions 同一批待确认操作的决定集合，用于恢复原执行。
     * @return 按返回类型约定组织的结果映射。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static Map<String, Boolean> choices(List<ApprovalChoiceCommand> decisions) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        if (decisions == null) return values;
        for (ApprovalChoiceCommand choice : decisions) {
            if (choice == null
                    || choice.getApprovalId() == null
                    || values.put(choice.getApprovalId(), choice.isApproved()) != null) {
                throw new ApplicationError(ApplicationError.Code.INVALID_ARGUMENT, "审批决定存在重复或空 ID");
            }
        }
        return values;
    }
}
