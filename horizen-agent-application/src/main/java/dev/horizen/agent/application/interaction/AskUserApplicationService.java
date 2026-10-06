package dev.horizen.agent.application.interaction;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.session.SessionExecutionView;
import dev.horizen.agent.common.validation.Preconditions;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.transaction.UnitOfWork;

import lombok.Value;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ask_user 回答用例及其状态迁移。
 */
public final class AskUserApplicationService {
    /**
     * 会话对象或会话索引，按相应的归属键定位数据。
     */
    private final SessionTurnStore sessions;

    /**
     * 按归属保存和查询澄清请求的仓储。
     */
    private final AskUserStore asks;

    /**
     * 验证澄清回答并编码运行时恢复负载的转换器。
     */
    private final AskUserAnswerEncoder answerEncoder;

    /**
     * 在交互事务提交后恢复原执行的入口。
     */
    private final AskUserTurnResumer resumer;

    /**
     * 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     */
    private final String executorId;

    /**
     * 执行实例租约的有效时长。
     */
    private final Duration leaseTtl;

    /**
     * 时间来源，用于计算更新时间、过期时间或执行时限。
     */
    private final Clock clock;

    /**
     * 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     */
    private final UnitOfWork transactions;

    /**
     * 创建提问用户应用服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions      会话对象或会话索引，按相应的归属键定位数据。
     * @param asks          提供提问集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param answerEncoder 当前提问用户应用服务持有的回答编码器对象，供相应处理步骤使用。
     * @param resumer       当前提问用户应用服务持有的恢复入口对象，供相应处理步骤使用。
     * @param transactions  执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     * @param executorId    持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseTtl      执行实例租约的有效时长。
     */
    public AskUserApplicationService(
            SessionTurnStore sessions,
            AskUserStore asks,
            AskUserAnswerEncoder answerEncoder,
            AskUserTurnResumer resumer,
            UnitOfWork transactions,
            String executorId,
            Duration leaseTtl) {
        this(
                sessions,
                asks,
                answerEncoder,
                resumer,
                transactions,
                executorId,
                leaseTtl,
                Clock.systemUTC());
    }

    /**
     * 创建提问用户应用服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions      会话对象或会话索引，按相应的归属键定位数据。
     * @param asks          提供提问集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param answerEncoder 当前提问用户应用服务持有的回答编码器对象，供相应处理步骤使用。
     * @param resumer       当前提问用户应用服务持有的恢复入口对象，供相应处理步骤使用。
     * @param transactions  执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     * @param executorId    持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseTtl      执行实例租约的有效时长。
     * @param clock         时间来源，用于计算更新时间、过期时间或执行时限。
     */
    public AskUserApplicationService(
            SessionTurnStore sessions,
            AskUserStore asks,
            AskUserAnswerEncoder answerEncoder,
            AskUserTurnResumer resumer,
            UnitOfWork transactions,
            String executorId,
            Duration leaseTtl,
            Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.asks = Objects.requireNonNull(asks, "asks");
        this.answerEncoder = Objects.requireNonNull(answerEncoder, "answerEncoder");
        this.resumer = Objects.requireNonNull(resumer, "resumer");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.executorId = Preconditions.requireText(executorId, "executorId 不能为空");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 校验并提交澄清回答；持久化成功后才恢复原执行。
     *
     * @param identity  可信宿主解析的执行身份，供访问范围与审计使用。
     * @param askUserId 待回答澄清请求的标识，恢复时与原问题记录关联。
     * @param answers   用户对澄清问题的回答集合，按问题标识关联选项和补充文本。
     * @param skip      是否跳过本次澄清请求；跳过与提交具体答案分开表示。
     * @return 本次操作返回的会话执行视图结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SessionExecutionView answer(
            ExecutionIdentity identity,
            String askUserId,
            List<Map<String, Object>> answers,
            boolean skip) {
        if (askUserId == null || askUserId.isBlank()) {
            throw new ApplicationError(ApplicationError.Code.INVALID_ARGUMENT, "askUserId 不能为空");
        }
        AskUserRequest original = findAsk(identity.getOwnerKey(), askUserId);
        CommittedAnswer committed =
                transactions.execute(() -> answerInTransaction(identity, askUserId, answers, skip));
        // 两条数据库记录均提交后才调度执行；执行过程不持有 SQL 锁。
        if (committed != null) {
            if (committed.expired) {
                resumer.timeout(identity, committed.turn, committed.ask);
                throw new ApplicationError(ApplicationError.Code.EXPIRED, "问题单已过期");
            }
            resumer.resume(identity, committed.turn, committed.ask, committed.answersJson);
        }
        return execution(identity.getOwnerKey(), original.getSessionId());
    }

    /**
     * 在同一工作单元中提交回答与执行状态，保留原交互和原执行的关联。
     *
     * @param identity  可信宿主解析的执行身份，供访问范围与审计使用。
     * @param askUserId 待回答澄清请求的标识，恢复时与原问题记录关联。
     * @param answers   用户对澄清问题的回答集合，按问题标识关联选项和补充文本。
     * @param skip      是否跳过本次澄清请求；跳过与提交具体答案分开表示。
     * @return 本次操作返回的已提交回答结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private CommittedAnswer answerInTransaction(
            ExecutionIdentity identity,
            String askUserId,
            List<Map<String, Object>> answers,
            boolean skip) {
        AskUserRequest ask = findAsk(identity.getOwnerKey(), askUserId);
        if (ask.getStatus() != AskUserStatus.PENDING) return null;
        AgentTurn turn =
                sessions.findTurn(identity.getOwnerKey(), ask.getTurnId())
                        .orElseThrow(
                                () ->
                                        new ApplicationError(
                                                ApplicationError.Code.NOT_FOUND, "关联 Turn 不存在"));
        if (!turn.getSessionId().equals(ask.getSessionId())) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "问题单与 Turn 不匹配");
        }
        if (turn.getStatus() != TurnStatus.WAITING_ASK_USER) return null;
        Instant now = clock.instant();
        boolean expired =
                !now.isBefore(ask.getExpiresAt())
                        || (turn.getDeadlineAt() != null && !now.isBefore(turn.getDeadlineAt()));
        String encoded =
                skip || expired
                        ? "[]"
                        : answerEncoder.validateAndEncode(
                        ask.getQuestionsJson(), answers == null ? List.of() : answers);
        // 与审批处理保持一致，按 Session/Turn → interaction 的顺序加锁。
        TransitionTurnResult transitioned =
                sessions.transitionTurn(
                        new TransitionTurnCommand(
                                identity.getOwnerKey(),
                                ask.getSessionId(),
                                ask.getTurnId(),
                                expired ? TurnStatus.TIMED_OUT : TurnStatus.RUNNING,
                                expired ? null : executorId,
                                expired ? null : now.plus(leaseTtl),
                                now,
                                expired ? "ASK_USER_EXPIRED" : null,
                                null,
                                null)
                                .expectVersion(turn.getVersion()));
        if (transitioned.getOutcome() != TransitionTurnResult.Outcome.UPDATED) return null;
        AskUserRequest resolved =
                asks.resolve(
                        identity.getOwnerKey(),
                        ask.getAskUserId(),
                        expired
                                ? AskUserStatus.EXPIRED
                                : skip ? AskUserStatus.SKIPPED : AskUserStatus.ANSWERED,
                        encoded,
                        now,
                        ask.getVersion());
        return new CommittedAnswer(transitioned.getTurn(), resolved, encoded, expired);
    }

    /**
     * 查找提问。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param askUserId 待回答澄清请求的标识，恢复时与原问题记录关联。
     * @return 本次操作返回的提问用户请求结果。
     */
    private AskUserRequest findAsk(String ownerKey, String askUserId) {
        return asks.find(ownerKey, askUserId)
                .orElseThrow(() -> new ApplicationError(ApplicationError.Code.NOT_FOUND, "问题不存在"));
    }

    /**
     * 回答事务提交后用于恢复原执行的信息。
     */
    @Value
    private static class CommittedAnswer {
        /**
         * 当前操作所关联的原执行事实。
         */
        AgentTurn turn;

        /**
         * 本次回答需要恢复的原澄清请求。
         */
        AskUserRequest ask;

        /**
         * 回答集合的 JSON 表示，供持久化或协议转换使用。
         */
        String answersJson;

        /**
         * 过期的状态标记，用于选择当前组件的处理路径。
         */
        boolean expired;
    }

    /**
     * 返回回答处理后的最新执行状态投影。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
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
}
