package dev.horizen.agent.storage.jdbc.repository.session;

import dev.horizen.agent.execution.session.AgentSession;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.MessageRole;
import dev.horizen.agent.execution.session.MessageStatus;
import dev.horizen.agent.execution.session.SessionHistoryPage;
import dev.horizen.agent.execution.session.SessionHistoryQuery;
import dev.horizen.agent.execution.session.SessionHistoryRepository;
import dev.horizen.agent.execution.session.SessionStatus;
import dev.horizen.agent.execution.session.SessionTitlePolicy;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.StartTurnResult;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.converter.JdbcExecutionMappings;
import dev.horizen.agent.storage.jdbc.mapper.SessionTurnMapper;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;
import dev.horizen.agent.transaction.UnitOfWork;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/** 使用关系数据库事务实现 Session 与 Turn 的事实存储。 */
public class JdbcSessionTurnStore implements SessionTurnStore, SessionHistoryRepository {
    /** 按归属检索和读取正式会话历史的仓储。 */
    private final SessionHistoryRepository historyRecall;

    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final SessionTurnMapper mapper;

    /** 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。 */
    private final UnitOfWork transactions;

    /**
     * 创建JDBC会话执行存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    public JdbcSessionTurnStore(DataSource dataSource) {
        this(dataSource, Duration.ofSeconds(3));
    }

    /**
     * 创建JDBC会话执行存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     * @param leaseQueryTimeout 租约查询允许持续的最长等待时间。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public JdbcSessionTurnStore(DataSource dataSource, Duration leaseQueryTimeout) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(leaseQueryTimeout, "leaseQueryTimeout");
        if (leaseQueryTimeout.isNegative() || leaseQueryTimeout.isZero()) {
            throw new IllegalArgumentException("leaseQueryTimeout must be positive");
        }
        this.mapper =
                MyBatisSessions.create(dataSource, leaseQueryTimeout)
                        .getMapper(SessionTurnMapper.class);
        this.historyRecall = new JdbcSessionHistoryRepository(dataSource);
        this.transactions = new JdbcUnitOfWork(dataSource);
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcSessionTurnStore(
            SessionTurnMapper mapper,
            UnitOfWork transactions,
            SessionHistoryRepository historyRecall) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = Objects.requireNonNull(transactions);
        this.historyRecall = Objects.requireNonNull(historyRecall);
    }

    /**
     * 启动执行。
     *
     * @param command 当前JDBC会话执行存储持有的命令对象，供相应处理步骤使用。
     * @return 本次操作返回的启动执行结果结果。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public StartTurnResult startTurn(StartTurnCommand command) {
        Objects.requireNonNull(command, "command");
        return transactions.execute(() -> startTurnInTransaction(command));
    }

    /**
     * 启动执行输入侧事务。
     *
     * @param command 当前JDBC会话执行存储持有的命令对象，供相应处理步骤使用。
     * @return 本次操作返回的启动执行结果结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private StartTurnResult startTurnInTransaction(StartTurnCommand command) {
        String ownerKey = command.getIdentity().getOwnerKey();
        ensureSession(
                ownerKey,
                command.getSessionId(),
                command.getIdentity().getActorId(),
                command.getUserMessage(),
                command.getStartedAt());

        AgentSession session = lockSession(ownerKey, command.getSessionId()).orElseThrow();
        // 会话行锁已串行化该会话的启动。对请求唯一索引执行 FOR UPDATE 会增加 InnoDB 间隙锁，
        // 可能使同一用户的不同新会话在并发启动时死锁。
        Optional<AgentTurn> duplicate =
                findTurnByRequest(ownerKey, command.getSessionId(), command.getRequestId());
        if (duplicate.isPresent()) {
            return new StartTurnResult(
                    StartTurnResult.Outcome.DUPLICATE, duplicate.get(), session.getActiveTurnId());
        }
        if (session.getStatus() != SessionStatus.ACTIVE) {
            return new StartTurnResult(
                    StartTurnResult.Outcome.SESSION_ARCHIVED, null, session.getActiveTurnId());
        }
        if (session.getActiveTurnId() != null) {
            return new StartTurnResult(
                    StartTurnResult.Outcome.SESSION_BUSY, null, session.getActiveTurnId());
        }

        mapper.insertTurn(
                ownerKey,
                command.getTurnId(),
                command.getSessionId(),
                command.getRequestId(),
                command.getIdentity().getActorId(),
                TurnStatus.RUNNING.name(),
                command.getExecutorId(),
                JdbcExecutionMappings.timestamp(command.getStartedAt()),
                JdbcExecutionMappings.timestamp(command.getDeadlineAt()),
                JdbcExecutionMappings.timestamp(command.getLeaseExpiresAt()),
                JdbcExecutionMappings.timestamp(command.getStartedAt()),
                JdbcExecutionMappings.timestamp(command.getStartedAt()));

        long sequence = nextMessageSequence(ownerKey, command.getSessionId());
        insertMessage(
                ownerKey,
                command.getSessionId(),
                command.getTurnId(),
                command.getUserMessageId(),
                MessageRole.USER,
                command.getUserMessage(),
                sequence,
                command.getStartedAt());

        int updated =
                mapper.claimSessionForTurn(
                        command.getTurnId(),
                        sequence + 1,
                        JdbcExecutionMappings.timestamp(command.getStartedAt()),
                        JdbcExecutionMappings.timestamp(command.getStartedAt()),
                        ownerKey,
                        command.getSessionId());
        if (updated != 1) {
            throw new IllegalStateException("Session 原子占用失败");
        }
        AgentTurn turn = findTurn(ownerKey, command.getTurnId()).orElseThrow();
        return new StartTurnResult(StartTurnResult.Outcome.STARTED, turn, command.getTurnId());
    }

    /**
     * 转换状态并处理执行。
     *
     * @param command 当前JDBC会话执行存储持有的命令对象，供相应处理步骤使用。
     * @return 本次操作返回的状态转换执行结果结果。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public TransitionTurnResult transitionTurn(TransitionTurnCommand command) {
        Objects.requireNonNull(command, "command");
        return transactions.execute(() -> transitionInTransaction(command));
    }

    /**
     * 转换状态并处理输入侧事务。
     *
     * @param command 当前JDBC会话执行存储持有的命令对象，供相应处理步骤使用。
     * @return 本次操作返回的状态转换执行结果结果。
     */
    private TransitionTurnResult transitionInTransaction(TransitionTurnCommand command) {
        AgentSession session =
                lockSession(command.getOwnerKey(), command.getSessionId()).orElse(null);
        AgentTurn current = lockTurn(command.getOwnerKey(), command.getTurnId()).orElse(null);
        if (session == null
                || current == null
                || !current.getSessionId().equals(command.getSessionId())) {
            return new TransitionTurnResult(TransitionTurnResult.Outcome.NOT_FOUND, current);
        }
        if (current.getStatus() == command.getTargetStatus()) {
            return new TransitionTurnResult(TransitionTurnResult.Outcome.ALREADY_IN_STATE, current);
        }
        if (command.getExpectedVersion() != null
                && command.getExpectedVersion() != current.getVersion()) {
            return new TransitionTurnResult(TransitionTurnResult.Outcome.STATUS_CHANGED, current);
        }
        if (!command.getTurnId().equals(session.getActiveTurnId())) {
            return new TransitionTurnResult(TransitionTurnResult.Outcome.TURN_CHANGED, current);
        }
        if (!current.getStatus().canTransitionTo(command.getTargetStatus())) {
            return new TransitionTurnResult(TransitionTurnResult.Outcome.STATUS_CHANGED, current);
        }

        String executorId =
                switch (command.getTargetStatus()) {
                    case RUNNING -> command.getExecutorId();
                    case CANCELLING -> current.getExecutorId();
                    default -> null;
                };
        Instant leaseExpiresAt =
                switch (command.getTargetStatus()) {
                    case RUNNING -> command.getLeaseExpiresAt();
                    case CANCELLING -> current.getLeaseExpiresAt();
                    default -> null;
                };
        Instant finishedAt =
                command.getTargetStatus().isTerminal() ? command.getOccurredAt() : null;

        int updated =
                mapper.transitionTurn(
                        command.getTargetStatus().name(),
                        executorId,
                        JdbcExecutionMappings.nullableTimestamp(leaseExpiresAt),
                        JdbcExecutionMappings.nullableTimestamp(finishedAt),
                        command.getFailureCode(),
                        JdbcExecutionMappings.timestamp(command.getOccurredAt()),
                        command.getOwnerKey(),
                        command.getTurnId(),
                        current.getVersion());
        if (updated != 1) {
            return new TransitionTurnResult(TransitionTurnResult.Outcome.STATUS_CHANGED, current);
        }

        if (command.getTargetStatus() == TurnStatus.COMPLETED) {
            long sequence = nextMessageSequence(command.getOwnerKey(), command.getSessionId());
            insertMessage(
                    command.getOwnerKey(),
                    command.getSessionId(),
                    command.getTurnId(),
                    command.getAssistantMessageId(),
                    MessageRole.ASSISTANT,
                    command.getAssistantMessage(),
                    sequence,
                    command.getOccurredAt());
            updateSessionAfterTransition(command, sequence + 1);
        } else {
            updateSessionAfterTransition(command, null);
        }
        AgentTurn result = findTurn(command.getOwnerKey(), command.getTurnId()).orElseThrow();
        return new TransitionTurnResult(TransitionTurnResult.Outcome.UPDATED, result);
    }

    /**
     * 更新会话处理后状态转换。
     *
     * @param command 当前JDBC会话执行存储持有的命令对象，供相应处理步骤使用。
     * @param nextSequence 下一页查询或下一次回放可以继续使用的游标。
     */
    private void updateSessionAfterTransition(TransitionTurnCommand command, Long nextSequence) {
        int updated;
        if (command.getTargetStatus().isTerminal()) {
            if (nextSequence == null) {
                updated =
                        mapper.releaseSessionTurn(
                                JdbcExecutionMappings.timestamp(command.getOccurredAt()),
                                command.getOwnerKey(),
                                command.getSessionId(),
                                command.getTurnId());
            } else {
                updated =
                        mapper.releaseSessionTurnWithMessage(
                                nextSequence,
                                JdbcExecutionMappings.timestamp(command.getOccurredAt()),
                                JdbcExecutionMappings.timestamp(command.getOccurredAt()),
                                command.getOwnerKey(),
                                command.getSessionId(),
                                command.getTurnId());
            }
            requireSingleSessionUpdate(updated);
            return;
        }
        updated =
                mapper.touchActiveSession(
                        JdbcExecutionMappings.timestamp(command.getOccurredAt()),
                        command.getOwnerKey(),
                        command.getSessionId(),
                        command.getTurnId());
        requireSingleSessionUpdate(updated);
    }

    /**
     * 取得并校验Single会话更新。
     *
     * @param updated 当前JDBC会话执行存储使用的更新，供其处理与状态记录使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requireSingleSessionUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Session 状态更新失败");
        }
    }

    /**
     * 续期租约。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean renewLease(
            String ownerKey,
            String turnId,
            String executorId,
            Instant leaseExpiresAt,
            Instant updatedAt) {
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return renewLeases(Map.of(ownerKey, List.of(turnId)), executorId, leaseExpiresAt, updatedAt)
                == 1;
    }

    /**
     * 续期租约集合。
     *
     * @param turnIdsByOwner 执行标识集合按条件数据归属的索引映射，供按键查找或归并当前组件的数据。
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次操作返回的整数结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public int renewLeases(
            Map<String, List<String>> turnIdsByOwner,
            String executorId,
            Instant leaseExpiresAt,
            Instant updatedAt) {
        Objects.requireNonNull(turnIdsByOwner, "turnIdsByOwner");
        Map<String, List<String>> owners = new LinkedHashMap<>();
        turnIdsByOwner.forEach(
                (owner, ids) -> {
                    List<String> unique =
                            ids == null ? List.of() : ids.stream().distinct().toList();
                    if (owner != null && !owner.isBlank() && !unique.isEmpty()) {
                        owners.put(owner, unique);
                    }
                });
        if (owners.isEmpty()) return 0;
        if (!leaseExpiresAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("Lease expiry must be after renewal time");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("owners", owners);
        parameters.put("executor", executorId);
        parameters.put("expires", JdbcExecutionMappings.timestamp(leaseExpiresAt));
        parameters.put("now", JdbcExecutionMappings.timestamp(updatedAt));
        return mapper.renewLeases(parameters);
    }

    /**
     * 查找会话。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<AgentSession> findSession(String ownerKey, String sessionId) {
        return first(
                mapper.selectFindSession(ownerKey, sessionId).stream()
                        .map(JdbcExecutionMappings.SESSION)
                        .toList());
    }

    /**
     * 查找执行。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<AgentTurn> findTurn(String ownerKey, String turnId) {
        return first(
                mapper.selectFindTurn(ownerKey, turnId).stream()
                        .map(JdbcExecutionMappings.TURN)
                        .toList());
    }

    /**
     * 查找最近版本执行。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<AgentTurn> findLatestTurn(String ownerKey, String sessionId) {
        return first(
                mapper.selectFindLatestTurn(ownerKey, sessionId).stream()
                        .map(JdbcExecutionMappings.TURN)
                        .toList());
    }

    /**
     * 查询列表中的会话集合。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param limit 本次处理或返回数量上限。
     * @param offset 本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public List<AgentSession> listSessions(String ownerKey, int limit, int offset) {
        if (limit <= 0 || limit > 101) {
            throw new IllegalArgumentException("limit 必须在 1 到 101 之间");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负数");
        }
        return mapper
                .selectListSessions(ownerKey, SessionStatus.ACTIVE.name(), limit, offset)
                .stream()
                .map(JdbcExecutionMappings.SESSION)
                .toList();
    }

    /**
     * 检查renameSession对应的条件，供调用方选择后续处理分支。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param title 当前JDBC会话执行存储的可读标题，供宿主界面展示。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean renameSession(
            String ownerKey, String sessionId, String title, Instant updatedAt) {
        return mapper.updateRenameSession(
                        title,
                        JdbcExecutionMappings.timestamp(updatedAt),
                        ownerKey,
                        sessionId,
                        SessionStatus.ACTIVE.name())
                == 1;
    }

    /**
     * 设置会话置顶。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param pinned 会话是否置顶，影响会话目录展示顺序。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean setSessionPinned(
            String ownerKey, String sessionId, boolean pinned, Instant updatedAt) {
        return mapper.updateSetSessionPinned(
                        pinned,
                        JdbcExecutionMappings.timestamp(updatedAt),
                        ownerKey,
                        sessionId,
                        SessionStatus.ACTIVE.name())
                == 1;
    }

    /**
     * 检查archiveSession对应的条件，供调用方选择后续处理分支。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean archiveSession(String ownerKey, String sessionId, Instant updatedAt) {
        return mapper.updateArchiveSession(
                        SessionStatus.ARCHIVED.name(),
                        JdbcExecutionMappings.timestamp(updatedAt),
                        ownerKey,
                        sessionId,
                        SessionStatus.ACTIVE.name())
                == 1;
    }

    /**
     * 查询列表中的正式消息集合。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<ConversationMessage> listFinalMessages(String ownerKey, String sessionId) {
        return mapper.selectListFinalMessages(ownerKey, sessionId).stream()
                .map(JdbcExecutionMappings.MESSAGE)
                .toList()
                .stream()
                .filter(message -> message.getStatus() == MessageStatus.FINAL)
                .toList();
    }

    /**
     * 查询历史。
     *
     * @param query 当前JDBC会话执行存储持有的查询对象，供相应处理步骤使用。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<SessionHistoryPage> queryHistory(SessionHistoryQuery query) {
        return historyRecall.queryHistory(query);
    }

    /**
     * 查找过期租约集合。
     *
     * @param now 用于本次更新或过期判断的当前时间。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public List<AgentTurn> findExpiredLeases(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数");
        }
        return mapper
                .selectFindExpiredLeases(
                        TurnStatus.RUNNING.name(),
                        TurnStatus.CANCELLING.name(),
                        JdbcExecutionMappings.timestamp(now),
                        limit)
                .stream()
                .map(JdbcExecutionMappings.TURN)
                .toList();
    }

    /**
     * 查找超过时限执行集合。
     *
     * @param now 用于本次更新或过期判断的当前时间。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public List<AgentTurn> findOverdueTurns(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数");
        }
        return mapper
                .selectFindOverdueTurns(
                        TurnStatus.RUNNING.name(),
                        TurnStatus.WAITING_APPROVAL.name(),
                        TurnStatus.WAITING_ASK_USER.name(),
                        TurnStatus.CANCELLING.name(),
                        JdbcExecutionMappings.timestamp(now),
                        limit)
                .stream()
                .map(JdbcExecutionMappings.TURN)
                .toList();
    }

    /**
     * 确保会话。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param actorId 实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     * @param firstMessage 当前JDBC会话执行存储使用的首个消息，供其处理与状态记录使用。
     * @param now 用于本次更新或过期判断的当前时间。
     */
    private void ensureSession(
            String ownerKey, String sessionId, String actorId, String firstMessage, Instant now) {
        try {
            mapper.insertSession(
                    ownerKey,
                    sessionId,
                    SessionStatus.ACTIVE.name(),
                    actorId,
                    SessionTitlePolicy.fromFirstMessage(firstMessage),
                    JdbcExecutionMappings.timestamp(now),
                    JdbcExecutionMappings.timestamp(now),
                    JdbcExecutionMappings.timestamp(now));
        } catch (DuplicateKeyException ignored) {
            // 并发创建同一个 Session 时由随后执行的行锁统一串行化。
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcSessionTurnStore处理步骤使用。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    private Optional<AgentSession> lockSession(String ownerKey, String sessionId) {
        return first(
                mapper.selectLockSession(ownerKey, sessionId).stream()
                        .map(JdbcExecutionMappings.SESSION)
                        .toList());
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcSessionTurnStore处理步骤使用。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    private Optional<AgentTurn> lockTurn(String ownerKey, String turnId) {
        return first(
                mapper.selectLockTurn(ownerKey, turnId).stream()
                        .map(JdbcExecutionMappings.TURN)
                        .toList());
    }

    /**
     * 查找执行按条件请求。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param requestId 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    private Optional<AgentTurn> findTurnByRequest(
            String ownerKey, String sessionId, String requestId) {
        return first(
                mapper.selectFindTurnByRequest(ownerKey, sessionId, requestId).stream()
                        .map(JdbcExecutionMappings.TURN)
                        .toList());
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcSessionTurnStore处理步骤使用。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的长整型结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private long nextMessageSequence(String ownerKey, String sessionId) {
        Long result = mapper.selectNextMessageSequence(ownerKey, sessionId);
        if (result == null) {
            throw new IllegalStateException("Session 不存在");
        }
        return result;
    }

    /**
     * 完成当前操作的insertMessage步骤，按实现更新相应状态或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param messageId 会话消息的标识，用于历史查询与过程事件关联。
     * @param role 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param sequence 当前记录在对应序列中的位置，用于排序或继续读取。
     * @param now 用于本次更新或过期判断的当前时间。
     */
    private void insertMessage(
            String ownerKey,
            String sessionId,
            String turnId,
            String messageId,
            MessageRole role,
            String content,
            long sequence,
            Instant now) {
        mapper.insertMessage(
                ownerKey,
                sessionId,
                turnId,
                messageId,
                sequence,
                JdbcHistoryJson.encode(
                        Map.of(
                                "role",
                                role.name(),
                                "status",
                                MessageStatus.FINAL.name(),
                                "content",
                                content)),
                JdbcExecutionMappings.timestamp(now),
                JdbcExecutionMappings.timestamp(now));
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcSessionTurnStore处理步骤使用。
     *
     * @param values 本次批量处理的值集合。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }
}
