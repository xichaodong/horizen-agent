package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.application.interaction.AskUserApplicationService;
import dev.horizen.agent.application.interaction.AskUserTurnResumer;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcAskUserStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class AskUserTransactionTest {
    @Test
    void failedAnswerWriteRollsBackBothRecordsAndDoesNotDispatch() {
        Fixture f = new Fixture();
        AskUserStore failing =
                new AskUserStore() {
                    public AskUserRequest createOrFind(AskUserRequest r) {
                        return f.asks.createOrFind(r);
                    }

                    public Optional<AskUserRequest> find(String owner, String id) {
                        return f.asks.find(owner, id);
                    }

                    public List<AskUserRequest> findPending(
                            String owner, String session, String turn) {
                        return f.asks.findPending(owner, session, turn);
                    }

                    public AskUserRequest resolve(
                            String owner,
                            String id,
                            AskUserStatus status,
                            String answers,
                            Instant at,
                            long version) {
                        f.asks.resolve(owner, id, status, answers, at, version);
                        throw new IllegalStateException("synthetic failure after write");
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () -> f.service(failing).answer(f.identity, "ask", List.of(), false));
        assertEquals(
                TurnStatus.WAITING_ASK_USER,
                f.sessions.findTurn("owner", "turn").orElseThrow().getStatus());
        assertEquals(AskUserStatus.PENDING, f.asks.find("owner", "ask").orElseThrow().getStatus());
        assertEquals("[]", f.asks.find("owner", "ask").orElseThrow().getAnswersJson());
        assertEquals(0, f.resumed.get());
    }

    @Test
    void committedAnswerResumesOutsideTransactionAndDuplicateAnswerDoesNotResumeAgain() {
        Fixture f = new Fixture();
        var service = f.service(f.asks);
        service.answer(f.identity, "ask", List.of(), false);
        service.answer(f.identity, "ask", List.of(), false);
        assertEquals(1, f.resumed.get());
    }

    static class Fixture {
        final DriverManagerDataSource source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:ask_tx_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        final ExecutionIdentity identity = new ExecutionIdentity("owner", "actor");
        final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        final JdbcSessionTurnStore sessions;
        final JdbcAskUserStore asks;
        final AtomicInteger resumed = new AtomicInteger();

        Fixture() {
            new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                    .execute(source);
            sessions = new JdbcSessionTurnStore(source);
            asks = new JdbcAskUserStore(source);
            sessions.startTurn(
                    new StartTurnCommand(
                            identity,
                            "session",
                            "turn",
                            "request",
                            "instance",
                            "message",
                            "synthetic",
                            now,
                            now.plusSeconds(120),
                            now.plusSeconds(30)));
            sessions.transitionTurn(
                    new TransitionTurnCommand(
                            "owner",
                            "session",
                            "turn",
                            TurnStatus.WAITING_ASK_USER,
                            null,
                            null,
                            now,
                            null,
                            null,
                            null));
            asks.createOrFind(
                    new AskUserRequest(
                            "owner",
                            "session",
                            "turn",
                            "ask",
                            "reply",
                            "tool",
                            "[]",
                            "[]",
                            AskUserStatus.PENDING,
                            now,
                            now.plusSeconds(120),
                            null,
                            0));
        }

        AskUserApplicationService service(AskUserStore records) {
            return new AskUserApplicationService(
                    sessions,
                    records,
                    (questions, answers) -> "[]",
                    new AskUserTurnResumer() {
                        public void resume(
                                ExecutionIdentity i,
                                AgentTurn t,
                                AskUserRequest ask,
                                String answers) {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            assertEquals(
                                    TurnStatus.RUNNING,
                                    sessions.findTurn("owner", "turn").orElseThrow().getStatus());
                            assertEquals(
                                    AskUserStatus.ANSWERED,
                                    asks.find("owner", "ask").orElseThrow().getStatus());
                            resumed.incrementAndGet();
                        }

                        public void timeout(ExecutionIdentity i, AgentTurn t, AskUserRequest ask) {
                            fail("unexpected timeout");
                        }
                    },
                    new JdbcUnitOfWork(source),
                    "instance",
                    Duration.ofSeconds(30),
                    Clock.fixed(now, ZoneOffset.UTC));
        }
    }
}
