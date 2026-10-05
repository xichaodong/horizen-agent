package dev.horizen.agent.storage.jdbc.repository.interaction;

import dev.horizen.agent.interaction.approval.ApprovalDecisionCommand;
import dev.horizen.agent.interaction.approval.ApprovalDecisionResult;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.storage.jdbc.codec.JdbcInteractionJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.ApprovalMapper;
import dev.horizen.agent.storage.jdbc.model.InteractionRow;
import dev.horizen.agent.storage.jdbc.support.RowValues;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;
import dev.horizen.agent.transaction.UnitOfWork;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import javax.sql.DataSource;

/** MySQL 产品审批记录实现；AgentScope 的暂停状态仍由 AgentStateStore 保存。 */
public class JdbcApprovalStore implements ApprovalStore {

    /** 映射器的固定取值，用于相应策略和边界判断。 */
    private static final Function<InteractionRow, ApprovalRequest> MAPPER =
            rs -> {
                var request = JdbcInteractionJson.read(rs.getRequestJson());
                var response = JdbcInteractionJson.read(rs.getResponseJson());
                return new ApprovalRequest(
                                rs.getOwnerKey(),
                                rs.getSessionId(),
                                rs.getTurnId(),
                                rs.getInteractionId(),
                                rs.getReplyId(),
                                rs.getToolCallId(),
                                JdbcInteractionJson.text(request, "toolName"),
                                JdbcInteractionJson.text(request, "toolContent"),
                                JdbcInteractionJson.text(request, "toolArgumentsJson"),
                                ApprovalStatus.valueOf(rs.getStatus()),
                                JdbcInteractionJson.text(request, "requestedBy"),
                                RowValues.nullableInstant(rs.getExpiresAt()),
                                JdbcInteractionJson.text(response, "decidedBy"),
                                RowValues.nullableInstant(rs.getResolvedAt()),
                                RowValues.instant(rs.getCreatedAt()),
                                RowValues.instant(rs.getUpdatedAt()),
                                rs.getVersion())
                        .withPresentationJson(
                                JdbcInteractionJson.text(request, "presentationJson"));
            };

    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final ApprovalMapper mapper;

    /** 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。 */
    private final UnitOfWork transactions;

    /**
     * 创建JDBC审批存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    public JdbcApprovalStore(DataSource dataSource) {
        this.mapper = MyBatisSessions.create(dataSource).getMapper(ApprovalMapper.class);
        this.transactions = new JdbcUnitOfWork(dataSource);
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcApprovalStore(ApprovalMapper mapper, UnitOfWork transactions) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = Objects.requireNonNull(transactions);
    }

    /**
     * 创建待处理。
     *
     * @param approvals 审批存储或待处理审批集合，用于原执行的暂停与恢复。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public void createPending(List<ApprovalRequest> approvals) {
        if (approvals == null || approvals.isEmpty()) {
            return;
        }
        transactions.executeWithoutResult(() -> approvals.forEach(this::insertPending));
    }

    /**
     * 完成当前操作的insertPending步骤，按实现更新相应状态或依赖。
     *
     * @param approval 当前JDBC审批存储持有的审批对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void insertPending(ApprovalRequest approval) {
        String requestJson = requestJson(approval);
        try {
            mapper.updateInsertPending(
                    approval.getOwnerKey(),
                    approval.getApprovalId(),
                    approval.getSessionId(),
                    approval.getTurnId(),
                    approval.getRequestReplyId(),
                    approval.getToolCallId(),
                    requestJson,
                    nullableTimestamp(approval.getExpiresAt()),
                    timestamp(approval.getCreatedAt()),
                    timestamp(approval.getUpdatedAt()));
        } catch (DuplicateKeyException ignored) {
            var existing =
                    mapper
                            .selectInsertPending(
                                    approval.getOwnerKey(),
                                    approval.getTurnId(),
                                    approval.getToolCallId())
                            .stream()
                            .map(MAPPER)
                            .toList();
            if (existing.size() != 1
                    || !existing.get(0).getSessionId().equals(approval.getSessionId())
                    || !Objects.equals(
                            existing.get(0).getRequestReplyId(), approval.getRequestReplyId())
                    || !JdbcInteractionJson.read(requestJson(existing.get(0)))
                            .equals(JdbcInteractionJson.read(requestJson))) {
                throw new IllegalStateException(
                        "approval identity was reused with a different request");
            }
        }
    }

    /**
     * 生成当前操作所需的requestJson文本，供调用方继续处理。
     *
     * @param approval 当前JDBC审批存储持有的审批对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String requestJson(ApprovalRequest approval) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("toolName", approval.getToolName());
        request.put("toolContent", approval.getToolContent());
        request.put("toolArgumentsJson", approval.getToolArgumentsJson());
        request.put("requestedBy", approval.getRequestedBy());
        request.put("presentationJson", approval.getPresentationJson());
        return JdbcInteractionJson.encode(request);
    }

    /**
     * 查找待处理。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<ApprovalRequest> findPending(String ownerKey, String sessionId, String turnId) {
        return mapper
                .selectFindPending(ownerKey, sessionId, turnId, ApprovalStatus.PENDING.name())
                .stream()
                .map(MAPPER)
                .toList();
    }

    /**
     * 提交决定并处理JDBC审批存储。
     *
     * @param command 当前JDBC审批存储持有的命令对象，供相应处理步骤使用。
     * @return 本次操作返回的审批决定结果结果。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public ApprovalDecisionResult decide(ApprovalDecisionCommand command) {
        return transactions.execute(() -> decideInTransaction(command));
    }

    /**
     * 提交决定并处理输入侧事务。
     *
     * @param command 当前JDBC审批存储持有的命令对象，供相应处理步骤使用。
     * @return 本次操作返回的审批决定结果结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private ApprovalDecisionResult decideInTransaction(ApprovalDecisionCommand command) {
        Optional<ApprovalRequest> current =
                first(
                        mapper
                                .selectDecideInTransaction(
                                        command.getOwnerKey(), command.getApprovalId())
                                .stream()
                                .map(MAPPER)
                                .toList());
        if (current.isEmpty()) {
            return new ApprovalDecisionResult(ApprovalDecisionResult.Outcome.NOT_FOUND, null);
        }
        if (current.get().getStatus() != ApprovalStatus.PENDING) {
            return new ApprovalDecisionResult(
                    ApprovalDecisionResult.Outcome.ALREADY_DECIDED, current.get());
        }
        ApprovalStatus next =
                command.isApproved() ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED;
        int updatedRows =
                mapper.updateDecideInTransaction(
                        next.name(),
                        JdbcInteractionJson.encode(
                                Map.of(
                                        "decidedBy",
                                        command.getDecidedBy(),
                                        "approved",
                                        command.isApproved())),
                        timestamp(command.getDecidedAt()),
                        timestamp(command.getDecidedAt()),
                        command.getOwnerKey(),
                        command.getApprovalId(),
                        ApprovalStatus.PENDING.name(),
                        current.get().getVersion());
        if (updatedRows != 1) {
            throw new IllegalStateException(
                    "Approval conditional update did not affect exactly one row");
        }
        ApprovalRequest updated =
                first(
                                mapper
                                        .selectDecideInTransaction2(
                                                command.getOwnerKey(), command.getApprovalId())
                                        .stream()
                                        .map(MAPPER)
                                        .toList())
                        .orElseThrow();
        return new ApprovalDecisionResult(ApprovalDecisionResult.Outcome.UPDATED, updated);
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcApprovalStore处理步骤使用。
     *
     * @param values 本次批量处理的值集合。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcApprovalStore处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的时间戳结果。
     */
    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcApprovalStore处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的时间戳结果。
     */
    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : timestamp(value);
    }
}
