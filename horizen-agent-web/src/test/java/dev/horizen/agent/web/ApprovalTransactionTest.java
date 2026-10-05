package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.application.interaction.ApprovalApplicationService;
import dev.horizen.agent.application.interaction.ApprovalChoiceCommand;
import dev.horizen.agent.application.interaction.ApprovalResolution;
import dev.horizen.agent.application.interaction.ApprovalTurnResumer;
import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalDecisionCommand;
import dev.horizen.agent.interaction.approval.ApprovalDecisionResult;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcApprovalStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.sql.DataSource;

/** 使用合成数据的仓储测试；显式启用 MySQL 模式后，也仅使用本测试唯一所有者的记录。 */
class ApprovalTransactionTest {
    private DataSource dataSource;
    private JdbcSessionTurnStore sessions;
    private JdbcApprovalStore approvals;
    private ExecutionIdentity identity;
    private Instant now;
    private ExecutorService workers;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        identity = new ExecutionIdentity("approval-test-" + suffix, "synthetic-actor");
        if (Boolean.getBoolean("horizen.approval.mysql.live")) {
            Properties config = new Properties();
            try (var input = Files.newInputStream(Path.of("..", ".env.yml"))) {
                config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
            }
            dataSource =
                    new DriverManagerDataSource(
                            config.getProperty("horizen.agent.storage.jdbc-url"),
                            config.getProperty("horizen.agent.storage.jdbc-username"),
                            config.getProperty("horizen.agent.storage.jdbc-password"));
        } else {
            dataSource =
                    new DriverManagerDataSource(
                            "jdbc:h2:mem:approval_"
                                    + suffix
                                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                            "sa",
                            "");
            new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                    .execute(dataSource);
        }
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        sessions = new JdbcSessionTurnStore(dataSource);
        approvals = new JdbcApprovalStore(dataSource);
        sessions.startTurn(
                new StartTurnCommand(
                        identity,
                        "session",
                        "turn",
                        "request",
                        "instance-start",
                        "user-message",
                        "synthetic approval test",
                        now,
                        now.plusSeconds(120),
                        now.plusSeconds(30)));
        sessions.transitionTurn(transition(TurnStatus.WAITING_APPROVAL));
        approvals.createPending(List.of(approval("approval-a"), approval("approval-b")));
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (workers != null) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (dataSource != null && identity != null) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            for (String table :
                    List.of("ha_interaction", "ha_conversation_history", "ha_turn", "ha_session")) {
                jdbc.update(
                        "DELETE FROM " + table + " WHERE owner_key = ?", identity.getOwnerKey());
            }
        }
    }

    @Test
    void concurrentOppositeDecisionsCommitOneConsistentBatchAndResumeOnce() throws Exception {
        CyclicBarrier bothReadPending = new CyclicBarrier(2);
        SessionTurnStore raced =
                proxy(
                        SessionTurnStore.class,
                        sessions,
                        (method, args) -> {
                            if (method.getName().equals("transitionTurn")
                                    && ((TransitionTurnCommand) args[0]).getTargetStatus()
                                            == TurnStatus.RUNNING) {
                                bothReadPending.await(10, TimeUnit.SECONDS);
                            }
                            return invoke(sessions, method, args);
                        });
        AtomicInteger resumeCount = new AtomicInteger();
        AtomicReference<List<Boolean>> resumed = new AtomicReference<>();
        ApprovalTurnResumer resumer =
                (i, turn, resolutions, remaining) -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    resumeCount.incrementAndGet();
                    resumed.set(resolutions.stream().map(ApprovalResolution::isApproved).toList());
                };
        var a = service(raced, approvals, resumer);
        var b = service(raced, new JdbcApprovalStore(dataSource), resumer);
        Future<?> reject =
                workers.submit(() -> a.decide(identity, "session", "turn", choices(false)));
        Future<?> approve =
                workers.submit(() -> b.decide(identity, "session", "turn", choices(true)));
        reject.get(15, TimeUnit.SECONDS);
        approve.get(15, TimeUnit.SECONDS);
        assertEquals(1, resumeCount.get());
        assertEquals(TurnStatus.RUNNING, current().getStatus());
        List<String> stored = statuses();
        assertEquals(stored.get(0), stored.get(1));
        assertEquals(
                List.of(stored.get(0).equals("APPROVED"), stored.get(1).equals("APPROVED")),
                resumed.get());
    }

    @Test
    void duplicateOppositeSubmissionDoesNotOverwriteOrDispatchAgain() {
        AtomicInteger resumed = new AtomicInteger();
        var service = service(sessions, approvals, (i, t, r, d) -> resumed.incrementAndGet());
        service.decide(identity, "session", "turn", choices(false));
        service.decide(identity, "session", "turn", choices(true));
        assertEquals(1, resumed.get());
        assertEquals(List.of("DENIED", "DENIED"), statuses());
    }

    @Test
    void storedRejectionWinsEvenWhenCallerSubmitsApproval() {
        ApprovalStore decidedElsewhere =
                proxy(
                        ApprovalStore.class,
                        approvals,
                        (method, args) -> {
                            if (method.getName().equals("decide")) {
                                ApprovalDecisionCommand request = (ApprovalDecisionCommand) args[0];
                                var committed =
                                        approvals.decide(
                                                new ApprovalDecisionCommand(
                                                        request.getOwnerKey(),
                                                        request.getApprovalId(),
                                                        false,
                                                        "synthetic-other-actor",
                                                        request.getDecidedAt()));
                                return new ApprovalDecisionResult(
                                        ApprovalDecisionResult.Outcome.ALREADY_DECIDED,
                                        committed.getApproval());
                            }
                            return invoke(approvals, method, args);
                        });
        AtomicReference<List<Boolean>> resumed = new AtomicReference<>();
        service(
                        sessions,
                        decidedElsewhere,
                        (i, t, r, d) ->
                                resumed.set(
                                        r.stream().map(ApprovalResolution::isApproved).toList()))
                .decide(identity, "session", "turn", choices(true));
        assertEquals(List.of(false, false), resumed.get());
        assertEquals(List.of("DENIED", "DENIED"), statuses());
    }

    @Test
    void secondDecisionFailureRollsBackTurnAndEarlierDecisionThenAllowsRetry() {
        AtomicInteger decisions = new AtomicInteger();
        AtomicInteger resumed = new AtomicInteger();
        ApprovalStore failing =
                proxy(
                        ApprovalStore.class,
                        approvals,
                        (method, args) -> {
                            if (method.getName().equals("decide")
                                    && decisions.incrementAndGet() == 2) {
                                throw new IllegalStateException(
                                        "synthetic second-decision failure");
                            }
                            return invoke(approvals, method, args);
                        });
        assertThrows(
                IllegalStateException.class,
                () ->
                        service(sessions, failing, (i, t, r, d) -> resumed.incrementAndGet())
                                .decide(identity, "session", "turn", choices(true)));
        assertEquals(TurnStatus.WAITING_APPROVAL, current().getStatus());
        assertEquals(List.of("PENDING", "PENDING"), statuses());
        assertEquals(0, resumed.get());
        service(sessions, approvals, (i, t, r, d) -> resumed.incrementAndGet())
                .decide(identity, "session", "turn", choices(true));
        assertEquals(1, resumed.get());
        assertEquals(List.of("APPROVED", "APPROVED"), statuses());
    }

    @Test
    void turnUpdateFailureRollsBackAndKeepsApprovalsPending() {
        SessionTurnStore failing =
                proxy(
                        SessionTurnStore.class,
                        sessions,
                        (method, args) -> {
                            Object result = invoke(sessions, method, args);
                            if (method.getName().equals("transitionTurn")) {
                                throw new IllegalStateException(
                                        "synthetic failure after turn update");
                            }
                            return result;
                        });
        assertThrows(
                IllegalStateException.class,
                () ->
                        service(
                                        failing,
                                        approvals,
                                        (i, t, r, d) ->
                                                fail("Must not dispatch an uncommitted recovery"))
                                .decide(identity, "session", "turn", choices(true)));
        assertEquals(TurnStatus.WAITING_APPROVAL, current().getStatus());
        assertEquals(List.of("PENDING", "PENDING"), statuses());
    }

    @Test
    void zeroRowApprovalUpdateIsRejectedAndWholeTransactionRollsBack() {
        DataSource zeroRows =
                proxy(
                        DataSource.class,
                        dataSource,
                        (method, args) -> {
                            Object result = invoke(dataSource, method, args);
                            if (!method.getName().equals("getConnection")) return result;
                            Connection connection = (Connection) result;
                            return proxy(
                                    Connection.class,
                                    connection,
                                    (operation, values) -> {
                                        Object prepared = invoke(connection, operation, values);
                                        if (operation.getName().equals("prepareStatement")
                                                && ((String) values[0])
                                                        .stripLeading()
                                                        .startsWith("UPDATE ha_interaction")) {
                                            PreparedStatement statement =
                                                    (PreparedStatement) prepared;
                                            return proxy(
                                                    PreparedStatement.class,
                                                    statement,
                                                    (execution, parameters) ->
                                                            switch (execution.getName()) {
                                                                case "executeUpdate",
                                                                        "getUpdateCount" ->
                                                                        0;
                                                                case "execute" -> false;
                                                                default ->
                                                                        invoke(
                                                                                statement,
                                                                                execution,
                                                                                parameters);
                                                            });
                                        }
                                        return prepared;
                                    });
                        });
        var service =
                new ApprovalApplicationService(
                        new JdbcSessionTurnStore(zeroRows),
                        new JdbcApprovalStore(zeroRows),
                        (i, t, r, d) -> fail("Zero-row update must not dispatch"),
                        new JdbcUnitOfWork(zeroRows),
                        "instance-test",
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(2));
        assertThrows(
                IllegalStateException.class,
                () -> service.decide(identity, "session", "turn", choices(true)));
        assertEquals(TurnStatus.WAITING_APPROVAL, current().getStatus());
        assertEquals(List.of("PENDING", "PENDING"), statuses());
    }

    @Test
    void runtimeIsDispatchedOnlyAfterCommittedStateIsVisibleOutsideTransaction() {
        AtomicInteger resumed = new AtomicInteger();
        service(
                        sessions,
                        approvals,
                        (i, turn, resolutions, remaining) -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            assertEquals(TurnStatus.RUNNING, current().getStatus());
                            assertEquals(List.of("APPROVED", "APPROVED"), statuses());
                            assertEquals(120, remaining.toSeconds());
                            resumed.incrementAndGet();
                        })
                .decide(identity, "session", "turn", choices(true));
        assertEquals(1, resumed.get());
    }

    @Test
    void staleVersionCannotClaimASecondWaitRound() {
        long firstRound = current().getVersion();
        sessions.transitionTurn(transition(TurnStatus.RUNNING));
        sessions.transitionTurn(transition(TurnStatus.WAITING_APPROVAL));
        var stale =
                sessions.transitionTurn(transition(TurnStatus.RUNNING).expectVersion(firstRound));
        assertEquals(TransitionTurnResult.Outcome.STATUS_CHANGED, stale.getOutcome());
        assertEquals(TurnStatus.WAITING_APPROVAL, current().getStatus());
    }

    private ApprovalApplicationService service(
            SessionTurnStore store, ApprovalStore records, ApprovalTurnResumer resumer) {
        return new ApprovalApplicationService(
                store,
                records,
                resumer,
                new JdbcUnitOfWork(dataSource),
                "instance-test",
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private AgentTurn current() {
        return sessions.findTurn(identity.getOwnerKey(), "turn").orElseThrow();
    }

    private List<String> statuses() {
        return new JdbcTemplate(dataSource)
                .queryForList(
                        "SELECT status FROM ha_interaction WHERE owner_key = ? AND interaction_type ="
                                + " 'APPROVAL' ORDER BY interaction_id",
                        String.class,
                        identity.getOwnerKey());
    }

    private List<ApprovalChoiceCommand> choices(boolean approved) {
        return List.of(
                new ApprovalChoiceCommand("approval-a", approved),
                new ApprovalChoiceCommand("approval-b", approved));
    }

    private TransitionTurnCommand transition(TurnStatus status) {
        return new TransitionTurnCommand(
                identity.getOwnerKey(),
                "session",
                "turn",
                status,
                status == TurnStatus.RUNNING ? "instance-test" : null,
                status == TurnStatus.RUNNING ? now.plusSeconds(30) : null,
                now,
                null,
                null,
                null);
    }

    private ApprovalRequest approval(String id) {
        return new ApprovalRequest(
                identity.getOwnerKey(),
                "session",
                "turn",
                id,
                "reply",
                "call-" + id,
                "synthetic-write",
                "",
                "{}",
                ApprovalStatus.PENDING,
                identity.getActorId(),
                now.plusSeconds(120),
                null,
                null,
                now,
                now,
                0);
    }

    @FunctionalInterface
    private interface Operation {
        Object apply(Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, T delegate, Operation operation) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (p, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "equals" -> p == args[0];
                                    case "hashCode" -> System.identityHashCode(p);
                                    case "toString" -> "synthetic-" + type.getSimpleName();
                                    default -> invoke(delegate, method, args);
                                };
                            }
                            return operation.apply(method, args);
                        }));
    }

    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
