package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.interaction.approval.ApprovalDecisionCommand;
import dev.horizen.agent.interaction.approval.ApprovalDecisionResult;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcApprovalStore;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcAskUserStore;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

class JdbcInteractionStoreTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private JdbcApprovalStore approvals;
    private JdbcAskUserStore asks;
    private final Instant now = Instant.parse("2026-10-03T00:00:00Z");

    @BeforeEach
    void setUp() {
        source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:interaction-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        approvals = new JdbcApprovalStore(source);
        asks = new JdbcAskUserStore(source);
    }

    @Test
    void typesAndOwnersWithTheSameIdentifiersRemainIndependent() {
        approvals.createPending(List.of(approval("owner", "same", "call", "{\"value\":42}")));
        asks.createOrFind(question("owner", "same", "call", "[]"));
        approvals.createPending(List.of(approval("other", "same", "call", "{}")));
        asks.createOrFind(question("other", "same", "call", "[]"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM ha_interaction", Integer.class));
        assertEquals(1, approvals.findPending("owner", "session", "turn").size());
        assertEquals(1, asks.findPending("owner", "session", "turn").size());
        approvals.decide(decision("owner", "same"));
        assertEquals(AskUserStatus.PENDING, asks.find("owner", "same").orElseThrow().getStatus());
        assertEquals(1, approvals.findPending("other", "session", "turn").size());
        assertTrue(asks.find("unknown", "same").isEmpty());
    }

    @Test
    void replayKeepsTheFrozenRequestAndNeverResetsResolvedState() {
        var approval =
                approval("owner", "approval", "call-a", "{\"value\":42}")
                        .withPresentationJson("{\"title\":\"frozen\"}");
        approvals.createPending(List.of(approval));
        approvals.createPending(List.of(approval));
        var question = question("owner", "question", "call-q", "[{\"question\":\"scope?\"}]");
        asks.createOrFind(question);
        assertThrows(
                IllegalStateException.class,
                () ->
                        approvals.createPending(
                                List.of(
                                        approval(
                                                "owner", "approval", "call-a", "{\"value\":99}"))));
        assertThrows(
                IllegalStateException.class,
                () ->
                        asks.createOrFind(
                                question(
                                        "owner",
                                        "question",
                                        "call-q",
                                        "[{\"question\":\"changed\"}]")));
        var approved = approvals.decide(decision("owner", "approval")).getApproval();
        assertEquals("{\"value\":42}", approved.getToolArgumentsJson());
        assertEquals("{\"title\":\"frozen\"}", approved.getPresentationJson());
        assertEquals("operator", approved.getDecidedBy());
        assertEquals(now.plusSeconds(1), approved.getDecidedAt());
        assertEquals(1, approved.getVersion());
        asks.resolve(
                "owner", "question", AskUserStatus.ANSWERED, "[\"full\"]", now.plusSeconds(1), 0);
        approvals.createPending(List.of(approval));
        assertEquals(
                ApprovalDecisionResult.Outcome.ALREADY_DECIDED,
                approvals.decide(decision("owner", "approval")).getOutcome());
        assertEquals("[\"full\"]", asks.createOrFind(question).getAnswersJson());
        assertTrue(asks.findPending("owner", "session", "turn").isEmpty());
    }

    @Test
    void concurrentApprovalCanOnlyBeDecidedOnce() throws Exception {
        approvals.createPending(List.of(approval("owner", "approval", "call", "{}")));
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            var work =
                    (Callable<ApprovalDecisionResult.Outcome>)
                            () -> {
                                ready.countDown();
                                go.await();
                                return new JdbcApprovalStore(source)
                                        .decide(decision("owner", "approval"))
                                        .getOutcome();
                            };
            var a = pool.submit(work);
            var b = pool.submit(work);
            ready.await();
            go.countDown();
            var outcomes = List.of(a.get(), b.get());
            assertTrue(outcomes.contains(ApprovalDecisionResult.Outcome.UPDATED));
            assertTrue(outcomes.contains(ApprovalDecisionResult.Outcome.ALREADY_DECIDED));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM ha_interaction", Long.class));
    }

    @Test
    void concurrentAnswersUseVersionCompareAndSet() throws Exception {
        asks.createOrFind(question("owner", "question", "call", "[]"));
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            var work =
                    (Callable<Boolean>)
                            () -> {
                                ready.countDown();
                                go.await();
                                try {
                                    new JdbcAskUserStore(source)
                                            .resolve(
                                                    "owner",
                                                    "question",
                                                    AskUserStatus.ANSWERED,
                                                    "[\"full\"]",
                                                    now,
                                                    0);
                                    return true;
                                } catch (IllegalStateException changed) {
                                    return false;
                                }
                            };
            var a = pool.submit(work);
            var b = pool.submit(work);
            ready.await();
            go.countDown();
            assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, asks.find("owner", "question").orElseThrow().getVersion());
    }

    @Test
    void wrongTypeStaleVersionAndPendingResolutionCannotUpdateAnotherRequest() {
        approvals.createPending(List.of(approval("owner", "approval-only", "call-a", "{}")));
        asks.createOrFind(question("owner", "question-only", "call-q", "[]"));
        assertEquals(
                ApprovalDecisionResult.Outcome.NOT_FOUND,
                approvals.decide(decision("owner", "question-only")).getOutcome());
        assertThrows(
                IllegalStateException.class,
                () -> asks.resolve("owner", "approval-only", AskUserStatus.ANSWERED, "[]", now, 0));
        assertThrows(
                IllegalStateException.class,
                () -> asks.resolve("owner", "question-only", AskUserStatus.ANSWERED, "[]", now, 9));
        assertThrows(
                IllegalArgumentException.class,
                () -> asks.resolve("owner", "question-only", AskUserStatus.PENDING, "[]", now, 0));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ha_interaction WHERE status='PENDING' AND version=0",
                        Integer.class));
    }

    @Test
    void bothRepositoriesJoinTheSameUnitOfWorkAndRollBackTogether() {
        approvals.createPending(List.of(approval("owner", "approval", "call-a", "{}")));
        asks.createOrFind(question("owner", "question", "call-q", "[]"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new JdbcUnitOfWork(source)
                                .execute(
                                        () -> {
                                            approvals.decide(decision("owner", "approval"));
                                            asks.resolve(
                                                    "owner",
                                                    "question",
                                                    AskUserStatus.SKIPPED,
                                                    "[]",
                                                    now,
                                                    0);
                                            throw new IllegalStateException(
                                                    "synthetic later Turn failure");
                                        }));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ha_interaction WHERE status='PENDING' AND version=0",
                        Integer.class));
    }

    @Test
    void conflictingApprovalInABatchRollsBackEarlierInserts() {
        approvals.createPending(List.of(approval("owner", "original", "call-a", "{}")));
        assertThrows(
                IllegalStateException.class,
                () ->
                        approvals.createPending(
                                List.of(
                                        approval("owner", "new", "call-b", "{}"),
                                        approval(
                                                "owner",
                                                "collision",
                                                "call-a",
                                                "{\"changed\":true}"))));
        assertEquals(1, approvals.findPending("owner", "session", "turn").size());
    }

    private ApprovalRequest approval(String owner, String id, String call, String args) {
        return new ApprovalRequest(
                owner,
                "session",
                "turn",
                id,
                "reply",
                call,
                "synthetic_write",
                "frozen description",
                args,
                ApprovalStatus.PENDING,
                "requester",
                now.plusSeconds(60),
                null,
                null,
                now,
                now,
                0);
    }

    private AskUserRequest question(String owner, String id, String call, String questions) {
        return new AskUserRequest(
                owner,
                "session",
                "turn",
                id,
                "reply",
                call,
                questions,
                "[]",
                AskUserStatus.PENDING,
                now,
                now.plusSeconds(60),
                null,
                0);
    }

    private ApprovalDecisionCommand decision(String owner, String id) {
        return new ApprovalDecisionCommand(owner, id, true, "operator", now.plusSeconds(1));
    }
}
