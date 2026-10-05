package dev.horizen.agent.storage.jdbc.repository.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.StartTurnResult;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalDecisionCommand;
import dev.horizen.agent.interaction.approval.ApprovalDecisionResult;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcTurnTimelineStore;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcApprovalStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class JdbcSessionTurnStoreTest {

    private ExecutorService executor;
    private JdbcSessionTurnStore store;
    private JdbcApprovalStore approvals;
    private JdbcTurnTimelineStore timeline;

    @BeforeEach
    void setUp() {
        String database = "horizen_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + database
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(dataSource);
        store = new JdbcSessionTurnStore(dataSource);
        approvals = new JdbcApprovalStore(dataSource);
        timeline = new JdbcTurnTimelineStore(dataSource);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void startIsIdempotentAndRejectsAnotherActiveTurn() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        StartTurnResult started = store.startTurn(command("turn-1", "request-1", now));
        StartTurnResult duplicate = store.startTurn(command("turn-2", "request-1", now));
        StartTurnResult busy = store.startTurn(command("turn-3", "request-2", now));

        assertEquals(StartTurnResult.Outcome.STARTED, started.getOutcome());
        assertEquals(StartTurnResult.Outcome.DUPLICATE, duplicate.getOutcome());
        assertEquals("turn-1", duplicate.getTurn().getTurnId());
        assertEquals(StartTurnResult.Outcome.SESSION_BUSY, busy.getOutcome());
        assertEquals("turn-1", busy.getActiveTurnId());
        assertEquals(
                "turn-1",
                store.findSession("owner-1", "session-1").orElseThrow().getActiveTurnId());
    }

    @Test
    void approvalPauseResumeAndCompletionKeepOneProductTurn() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        store.startTurn(command("turn-1", "request-1", now));

        TransitionTurnResult waiting =
                store.transitionTurn(
                        new TransitionTurnCommand(
                                "owner-1",
                                "session-1",
                                "turn-1",
                                TurnStatus.WAITING_APPROVAL,
                                null,
                                null,
                                now.plusSeconds(2),
                                null,
                                null,
                                null));
        TransitionTurnResult resumed =
                store.transitionTurn(
                        new TransitionTurnCommand(
                                "owner-1",
                                "session-1",
                                "turn-1",
                                TurnStatus.RUNNING,
                                "instance-2",
                                now.plusSeconds(40),
                                now.plusSeconds(10),
                                null,
                                null,
                                null));
        TransitionTurnResult completed =
                store.transitionTurn(
                        new TransitionTurnCommand(
                                "owner-1",
                                "session-1",
                                "turn-1",
                                TurnStatus.COMPLETED,
                                null,
                                null,
                                now.plusSeconds(20),
                                null,
                                "message-a",
                                "完整回答"));

        assertEquals(TransitionTurnResult.Outcome.UPDATED, waiting.getOutcome());
        assertEquals(TransitionTurnResult.Outcome.UPDATED, resumed.getOutcome());
        assertEquals("instance-2", resumed.getTurn().getExecutorId());
        assertEquals(TransitionTurnResult.Outcome.UPDATED, completed.getOutcome());
        assertEquals(TurnStatus.COMPLETED, completed.getTurn().getStatus());
        assertNull(store.findSession("owner-1", "session-1").orElseThrow().getActiveTurnId());

        var messages = store.listFinalMessages("owner-1", "session-1");
        assertEquals(2, messages.size());
        assertEquals("帮我诊断", messages.get(0).getContent());
        assertEquals("完整回答", messages.get(1).getContent());
        assertEquals(1, messages.get(0).getSequence());
        assertEquals(2, messages.get(1).getSequence());
    }

    @Test
    void onlyOneConcurrentStartCanOwnTheSession() throws Exception {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<StartTurnResult> first =
                executor.submit(
                        () -> {
                            ready.countDown();
                            go.await();
                            return store.startTurn(command("turn-a", "request-a", now));
                        });
        Future<StartTurnResult> second =
                executor.submit(
                        () -> {
                            ready.countDown();
                            go.await();
                            return store.startTurn(command("turn-b", "request-b", now));
                        });
        ready.await();
        go.countDown();

        List<StartTurnResult.Outcome> outcomes =
                List.of(first.get().getOutcome(), second.get().getOutcome());
        assertTrue(outcomes.contains(StartTurnResult.Outcome.STARTED));
        assertTrue(outcomes.contains(StartTurnResult.Outcome.SESSION_BUSY));
        assertNotNull(store.findSession("owner-1", "session-1").orElseThrow().getActiveTurnId());
    }

    @Test
    void leaseCanOnlyBeRenewedByTheExecutor() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        store.startTurn(command("turn-1", "request-1", now));

        assertFalse(
                store.renewLease(
                        "owner-1",
                        "turn-1",
                        "other-instance",
                        now.plusSeconds(60),
                        now.plusSeconds(10)));
        assertTrue(
                store.renewLease(
                        "owner-1",
                        "turn-1",
                        "instance-1",
                        now.plusSeconds(60),
                        now.plusSeconds(10)));
        assertEquals(
                now.plusSeconds(60),
                store.findTurn("owner-1", "turn-1").orElseThrow().getLeaseExpiresAt());
    }

    @Test
    void batchLeaseRenewalUpdatesOnlyLiveTurnsOwnedByThisExecutor() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        store.startTurn(command("owner-1", "session-1", "turn-1", "request-1", "instance-1", now));
        store.startTurn(command("owner-2", "session-2", "turn-2", "request-2", "instance-1", now));
        store.startTurn(
                command("owner-3", "session-3", "turn-3", "request-3", "other-instance", now));
        store.transitionTurn(
                new TransitionTurnCommand(
                        "owner-2",
                        "session-2",
                        "turn-2",
                        TurnStatus.WAITING_APPROVAL,
                        null,
                        null,
                        now.plusSeconds(5),
                        null,
                        null,
                        null));

        Instant renewedUntil = now.plusSeconds(70);
        int renewed =
                store.renewLeases(
                        Map.of(
                                "owner-1", List.of("turn-1", "turn-1"),
                                "owner-2", List.of("turn-2"),
                                "owner-3", List.of("turn-3")),
                        "instance-1",
                        renewedUntil,
                        now.plusSeconds(10));

        assertEquals(1, renewed);
        assertEquals(
                renewedUntil,
                store.findTurn("owner-1", "turn-1").orElseThrow().getLeaseExpiresAt());
        assertNull(store.findTurn("owner-2", "turn-2").orElseThrow().getLeaseExpiresAt());
        assertEquals(
                now.plusSeconds(30),
                store.findTurn("owner-3", "turn-3").orElseThrow().getLeaseExpiresAt());
    }

    @Test
    void batchLeaseRenewalDoesNotReviveExpiredTurn() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        store.startTurn(command("turn-1", "request-1", now));

        int renewed =
                store.renewLeases(
                        Map.of("owner-1", List.of("turn-1")),
                        "instance-1",
                        now.plusSeconds(90),
                        now.plusSeconds(31));

        assertEquals(0, renewed);
        assertEquals(
                now.plusSeconds(30),
                store.findTurn("owner-1", "turn-1").orElseThrow().getLeaseExpiresAt());
    }

    @Test
    void approvalCreationIsIdempotentAndDecisionIsAtomic() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        ApprovalRequest approval =
                new ApprovalRequest(
                        "owner-1",
                        "session-1",
                        "turn-1",
                        "approval-1",
                        "reply-1",
                        "tool-1",
                        "dangerous",
                        "{\"value\":1}",
                        "{\"value\":1}",
                        ApprovalStatus.PENDING,
                        "user-1",
                        now.plusSeconds(60),
                        null,
                        null,
                        now,
                        now,
                        0);

        approvals.createPending(List.of(approval));
        approvals.createPending(List.of(approval));
        assertEquals(1, approvals.findPending("owner-1", "session-1", "turn-1").size());

        var decided =
                approvals.decide(
                        new ApprovalDecisionCommand(
                                "owner-1", "approval-1", true, "approver", now.plusSeconds(1)));
        var repeated =
                approvals.decide(
                        new ApprovalDecisionCommand(
                                "owner-1", "approval-1", false, "approver", now.plusSeconds(2)));

        assertEquals(ApprovalStatus.APPROVED, decided.getApproval().getStatus());
        assertEquals(ApprovalDecisionResult.Outcome.ALREADY_DECIDED, repeated.getOutcome());
    }

    @Test
    void findsExpiredLeaseAndDeadlineForRecovery() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        store.startTurn(command("turn-1", "request-1", now));

        assertTrue(
                store.findExpiredLeases(now.plusSeconds(31), 10).stream()
                        .anyMatch(turn -> turn.getTurnId().equals("turn-1")));
        assertTrue(
                store.findOverdueTurns(now.plus(Duration.ofMinutes(11)), 10).stream()
                        .anyMatch(turn -> turn.getTurnId().equals("turn-1")));
    }

    @Test
    void sameSessionIdIsIndependentAcrossOwners() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        StartTurnResult first =
                store.startTurn(
                        new StartTurnCommand(
                                new ExecutionIdentity("owner-a", "actor-a"),
                                "shared-session",
                                "turn-a",
                                "request-a",
                                "instance-a",
                                "message-a",
                                "A",
                                now,
                                now.plusSeconds(600),
                                now.plusSeconds(30)));
        StartTurnResult second =
                store.startTurn(
                        new StartTurnCommand(
                                new ExecutionIdentity("owner-b", "actor-b"),
                                "shared-session",
                                "turn-b",
                                "request-b",
                                "instance-b",
                                "message-b",
                                "B",
                                now,
                                now.plusSeconds(600),
                                now.plusSeconds(30)));

        assertEquals(StartTurnResult.Outcome.STARTED, first.getOutcome());
        assertEquals(StartTurnResult.Outcome.STARTED, second.getOutcome());
        assertEquals(
                "turn-a",
                store.findSession("owner-a", "shared-session").orElseThrow().getActiveTurnId());
        assertEquals(
                "turn-b",
                store.findSession("owner-b", "shared-session").orElseThrow().getActiveTurnId());
    }

    @Test
    void sessionCatalogIsOwnerScopedMutableAndArchivedWithoutDeletingHistory() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        store.startTurn(command("turn-1", "request-1", now));
        store.transitionTurn(
                new TransitionTurnCommand(
                        "owner-1",
                        "session-1",
                        "turn-1",
                        TurnStatus.COMPLETED,
                        null,
                        null,
                        now.plusSeconds(5),
                        null,
                        "answer-1",
                        "完成"));

        var created = store.listSessions("owner-1", 20, 0);
        assertEquals(1, created.size());
        assertEquals("帮我诊断", created.get(0).getTitle());
        assertFalse(created.get(0).isPinned());

        assertTrue(store.renameSession("owner-1", "session-1", "新的标题", now.plusSeconds(6)));
        assertTrue(store.setSessionPinned("owner-1", "session-1", true, now.plusSeconds(7)));
        var updated = store.listSessions("owner-1", 20, 0).get(0);
        assertEquals("新的标题", updated.getTitle());
        assertTrue(updated.isPinned());
        assertTrue(store.listSessions("another-owner", 20, 0).isEmpty());

        assertTrue(store.archiveSession("owner-1", "session-1", now.plusSeconds(8)));
        assertTrue(store.listSessions("owner-1", 20, 0).isEmpty());
        assertEquals(2, store.listFinalMessages("owner-1", "session-1").size());
    }

    @Test
    void activeSessionCannotBeArchived() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        store.startTurn(command("turn-1", "request-1", now));

        assertFalse(store.archiveSession("owner-1", "session-1", now.plusSeconds(1)));
        assertEquals(1, store.listSessions("owner-1", 20, 0).size());
    }

    @Test
    void timelineEventsAreOrderedAndOwnerScoped() {
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        var first =
                timeline.append("owner-1", "session-1", "turn-1", "{\"type\":\"tool_start\"}", now);
        var second =
                timeline.append(
                        "owner-1",
                        "session-1",
                        "turn-1",
                        "{\"type\":\"tool_end\"}",
                        now.plusSeconds(1));

        assertTrue(second.getSequence() > first.getSequence());
        assertEquals(List.of(first, second), timeline.listForSession("owner-1", "session-1"));
        assertTrue(timeline.listForSession("owner-2", "session-1").isEmpty());
    }

    @Test
    void staleLeaseRecoveryCannotOverwriteAConcurrentRenewal() {
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        store.startTurn(command("turn-1", "request-1", now));
        var stale = store.findExpiredLeases(now.plusSeconds(31), 100).get(0);
        assertTrue(
                store.renewLease(
                        "owner-1",
                        "turn-1",
                        "instance-1",
                        now.plusSeconds(90),
                        now.plusSeconds(29)));
        var result =
                store.transitionTurn(
                        new TransitionTurnCommand(
                                        "owner-1",
                                        "session-1",
                                        "turn-1",
                                        TurnStatus.FAILED,
                                        null,
                                        null,
                                        now.plusSeconds(31),
                                        "EXECUTOR_LOST",
                                        null,
                                        null)
                                .expectVersion(stale.getVersion()));
        assertEquals(TransitionTurnResult.Outcome.STATUS_CHANGED, result.getOutcome());
        var current = store.findTurn("owner-1", "turn-1").orElseThrow();
        assertEquals(TurnStatus.RUNNING, current.getStatus());
        assertEquals(now.plusSeconds(90), current.getLeaseExpiresAt());
    }

    private static StartTurnCommand command(String turnId, String requestId, Instant now) {
        return command("owner-1", "session-1", turnId, requestId, "instance-1", now);
    }

    private static StartTurnCommand command(
            String ownerKey,
            String sessionId,
            String turnId,
            String requestId,
            String instanceId,
            Instant now) {
        return new StartTurnCommand(
                new ExecutionIdentity(ownerKey, "user-1"),
                sessionId,
                turnId,
                requestId,
                instanceId,
                "user-message-" + turnId,
                "帮我诊断",
                now,
                now.plus(Duration.ofMinutes(10)),
                now.plusSeconds(30));
    }
}
