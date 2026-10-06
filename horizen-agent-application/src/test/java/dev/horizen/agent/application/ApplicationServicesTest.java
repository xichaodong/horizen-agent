package dev.horizen.agent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.application.interaction.ApprovalApplicationService;
import dev.horizen.agent.application.interaction.ApprovalChoiceCommand;
import dev.horizen.agent.application.interaction.AskUserApplicationService;
import dev.horizen.agent.application.interaction.AskUserTurnResumer;
import dev.horizen.agent.application.session.SessionApplicationService;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.execution.session.AgentSession;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.SessionStatus;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
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
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.transaction.UnitOfWork;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

class ApplicationServicesTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ExecutionIdentity IDENTITY = new ExecutionIdentity("owner", "actor");

    @Test
    void sessionRulesAreAppliedBeforeRepositoryMutation() {
        MemorySessions sessions = new MemorySessions();
        sessions.session = session("turn-1");
        sessions.turns.put("turn-1", turn("turn-1", TurnStatus.RUNNING, NOW.plusSeconds(60)));
        SessionApplicationService service = new SessionApplicationService(sessions, CLOCK);

        ApplicationError active =
                assertThrows(ApplicationError.class, () -> service.archive("owner", "session"));
        assertEquals(ApplicationError.Code.CONFLICT, active.getCode());

        sessions.session = session(null);
        service.rename("owner", "session", "  新标题  ");
        service.setPinned("owner", "session", true);
        assertEquals("新标题", sessions.session.getTitle());
        assertTrue(sessions.session.isPinned());
    }

    @Test
    void approvalRequiresCompleteDecisionSetAndResumesThroughPort() {
        MemorySessions sessions = new MemorySessions();
        sessions.session = session("turn-1");
        sessions.turns.put(
                "turn-1", turn("turn-1", TurnStatus.WAITING_APPROVAL, NOW.plusSeconds(120)));
        MemoryApprovals approvals =
                new MemoryApprovals(List.of(approval("approval-1"), approval("approval-2")));
        List<String> resumed = new ArrayList<>();
        ApprovalApplicationService service =
                new ApprovalApplicationService(
                        sessions,
                        approvals,
                        (identity, turn, resolutions, remaining) -> {
                            resumed.add(turn.getTurnId());
                            assertEquals(2, resolutions.size());
                            assertEquals(Duration.ofSeconds(120), remaining);
                        },
                        new UnitOfWork() {
                            @Override
                            public <T> T execute(Supplier<T> work) {
                                return work.get();
                            }
                        },
                        "instance-a",
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(2),
                        CLOCK);

        ApplicationError incomplete =
                assertThrows(
                        ApplicationError.class,
                        () ->
                                service.decide(
                                        IDENTITY,
                                        "session",
                                        "turn-1",
                                        List.of(new ApprovalChoiceCommand("approval-1", true))));
        assertEquals(ApplicationError.Code.INVALID_ARGUMENT, incomplete.getCode());

        service.decide(
                IDENTITY,
                "session",
                "turn-1",
                List.of(
                        new ApprovalChoiceCommand("approval-1", true),
                        new ApprovalChoiceCommand("approval-2", false)));
        assertEquals(List.of("turn-1"), resumed);
        assertEquals(TurnStatus.RUNNING, sessions.turns.get("turn-1").getStatus());
        assertEquals(2, approvals.decided.size());
    }

    @Test
    void expiredAskUserTimesOutBeforeReturningGone() {
        MemorySessions sessions = new MemorySessions();
        sessions.session = session("turn-1");
        sessions.turns.put(
                "turn-1", turn("turn-1", TurnStatus.WAITING_ASK_USER, NOW.plusSeconds(120)));
        AskUserRequest ask =
                new AskUserRequest(
                        "owner",
                        "session",
                        "turn-1",
                        "ask-1",
                        "reply-1",
                        "tool-1",
                        "{}",
                        "[]",
                        AskUserStatus.PENDING,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(1),
                        null,
                        0);
        MemoryAsks asks = new MemoryAsks(ask);
        List<String> timedOut = new ArrayList<>();
        AskUserApplicationService service =
                new AskUserApplicationService(
                        sessions,
                        asks,
                        (questions, answers) -> "[]",
                        new AskUserTurnResumer() {
                            @Override
                            public void resume(
                                    ExecutionIdentity identity,
                                    AgentTurn turn,
                                    AskUserRequest request,
                                    String answersJson) {
                            }

                            @Override
                            public void timeout(
                                    ExecutionIdentity identity,
                                    AgentTurn turn,
                                    AskUserRequest request) {
                                timedOut.add(turn.getTurnId());
                            }
                        },
                        new UnitOfWork() {
                            @Override
                            public <T> T execute(Supplier<T> work) {
                                return work.get();
                            }
                        },
                        "instance-a",
                        Duration.ofSeconds(30),
                        CLOCK);

        ApplicationError expired =
                assertThrows(
                        ApplicationError.class,
                        () -> service.answer(IDENTITY, "ask-1", List.of(), false));
        assertEquals(ApplicationError.Code.EXPIRED, expired.getCode());
        assertEquals(List.of("turn-1"), timedOut);
        assertEquals(TurnStatus.TIMED_OUT, sessions.turns.get("turn-1").getStatus());
        assertEquals(AskUserStatus.EXPIRED, asks.current.getStatus());
    }

    private static AgentSession session(String activeTurnId) {
        return new AgentSession(
                "owner",
                "session",
                SessionStatus.ACTIVE,
                activeTurnId,
                "actor",
                "标题",
                false,
                NOW,
                NOW,
                NOW,
                0);
    }

    private static AgentTurn turn(String turnId, TurnStatus status, Instant deadline) {
        return new AgentTurn(
                "owner",
                "session",
                turnId,
                "request",
                "actor",
                status,
                status == TurnStatus.RUNNING ? "instance-a" : null,
                NOW.minusSeconds(10),
                status.isTerminal() ? NOW : null,
                deadline,
                status == TurnStatus.RUNNING ? NOW.plusSeconds(30) : null,
                null,
                NOW.minusSeconds(10),
                NOW.minusSeconds(10),
                0);
    }

    private static ApprovalRequest approval(String id) {
        return new ApprovalRequest(
                "owner",
                "session",
                "turn-1",
                id,
                "reply-1",
                "tool-" + id,
                "tool",
                "",
                "{}",
                ApprovalStatus.PENDING,
                "actor",
                NOW.plusSeconds(60),
                null,
                null,
                NOW,
                NOW,
                0);
    }

    private static final class MemorySessions implements SessionTurnStore {
        private AgentSession session;
        private final Map<String, AgentTurn> turns = new LinkedHashMap<>();

        @Override
        public Optional<AgentSession> findSession(String owner, String sessionId) {
            return Optional.ofNullable(session);
        }

        @Override
        public Optional<AgentTurn> findTurn(String owner, String turnId) {
            return Optional.ofNullable(turns.get(turnId));
        }

        @Override
        public Optional<AgentTurn> findLatestTurn(String owner, String sessionId) {
            return turns.values().stream().reduce((first, second) -> second);
        }

        @Override
        public List<AgentSession> listSessions(String owner, int limit, int offset) {
            return session == null || offset > 0 ? List.of() : List.of(session);
        }

        @Override
        public boolean renameSession(String owner, String sessionId, String title, Instant at) {
            if (session == null) return false;
            session =
                    new AgentSession(
                            owner,
                            sessionId,
                            session.getStatus(),
                            session.getActiveTurnId(),
                            session.getCreatedBy(),
                            title,
                            session.isPinned(),
                            session.getLastMessageAt(),
                            session.getCreatedAt(),
                            at,
                            session.getVersion() + 1);
            return true;
        }

        @Override
        public boolean setSessionPinned(
                String owner, String sessionId, boolean pinned, Instant at) {
            if (session == null) return false;
            session =
                    new AgentSession(
                            owner,
                            sessionId,
                            session.getStatus(),
                            session.getActiveTurnId(),
                            session.getCreatedBy(),
                            session.getTitle(),
                            pinned,
                            session.getLastMessageAt(),
                            session.getCreatedAt(),
                            at,
                            session.getVersion() + 1);
            return true;
        }

        @Override
        public boolean archiveSession(String owner, String sessionId, Instant at) {
            session =
                    new AgentSession(
                            owner,
                            sessionId,
                            SessionStatus.ARCHIVED,
                            null,
                            session.getCreatedBy(),
                            session.getTitle(),
                            session.isPinned(),
                            session.getLastMessageAt(),
                            session.getCreatedAt(),
                            at,
                            session.getVersion() + 1);
            return true;
        }

        @Override
        public TransitionTurnResult transitionTurn(TransitionTurnCommand command) {
            AgentTurn before = turns.get(command.getTurnId());
            AgentTurn after =
                    new AgentTurn(
                            command.getOwnerKey(),
                            command.getSessionId(),
                            command.getTurnId(),
                            before.getRequestId(),
                            before.getActorId(),
                            command.getTargetStatus(),
                            command.getExecutorId(),
                            before.getStartedAt(),
                            command.getTargetStatus().isTerminal() ? command.getOccurredAt() : null,
                            before.getDeadlineAt(),
                            command.getLeaseExpiresAt(),
                            command.getFailureCode(),
                            before.getCreatedAt(),
                            command.getOccurredAt(),
                            before.getVersion() + 1);
            turns.put(command.getTurnId(), after);
            return new TransitionTurnResult(TransitionTurnResult.Outcome.UPDATED, after);
        }

        @Override
        public StartTurnResult startTurn(StartTurnCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean renewLease(
                String owner, String turn, String executor, Instant lease, Instant at) {
            return false;
        }

        @Override
        public List<ConversationMessage> listFinalMessages(String owner, String sessionId) {
            return List.of();
        }

        @Override
        public List<AgentTurn> findExpiredLeases(Instant now, int limit) {
            return List.of();
        }

        @Override
        public List<AgentTurn> findOverdueTurns(Instant now, int limit) {
            return List.of();
        }
    }

    private static final class MemoryApprovals implements ApprovalStore {
        private final List<ApprovalRequest> pending;
        private final List<String> decided = new ArrayList<>();

        private MemoryApprovals(List<ApprovalRequest> pending) {
            this.pending = pending;
        }

        @Override
        public void createPending(List<ApprovalRequest> values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ApprovalRequest> findPending(String owner, String session, String turn) {
            return pending;
        }

        @Override
        public ApprovalDecisionResult decide(ApprovalDecisionCommand command) {
            decided.add(command.getApprovalId());
            ApprovalRequest stored =
                    pending.stream()
                            .filter(value -> value.getApprovalId().equals(command.getApprovalId()))
                            .findFirst()
                            .orElseThrow();
            stored =
                    new ApprovalRequest(
                            stored.getOwnerKey(),
                            stored.getSessionId(),
                            stored.getTurnId(),
                            stored.getApprovalId(),
                            stored.getRequestReplyId(),
                            stored.getToolCallId(),
                            stored.getToolName(),
                            stored.getToolContent(),
                            stored.getToolArgumentsJson(),
                            command.isApproved()
                                    ? ApprovalStatus.APPROVED
                                    : ApprovalStatus.DENIED,
                            stored.getRequestedBy(),
                            stored.getExpiresAt(),
                            command.getDecidedBy(),
                            command.getDecidedAt(),
                            stored.getCreatedAt(),
                            command.getDecidedAt(),
                            stored.getVersion() + 1)
                            .withPresentationJson(stored.getPresentationJson());
            return new ApprovalDecisionResult(ApprovalDecisionResult.Outcome.UPDATED, stored);
        }
    }

    private static final class MemoryAsks implements AskUserStore {
        private AskUserRequest current;

        private MemoryAsks(AskUserRequest current) {
            this.current = current;
        }

        @Override
        public AskUserRequest createOrFind(AskUserRequest request) {
            return current;
        }

        @Override
        public Optional<AskUserRequest> find(String owner, String askId) {
            return Optional.of(current);
        }

        @Override
        public List<AskUserRequest> findPending(String owner, String session, String turn) {
            return List.of(current);
        }

        @Override
        public AskUserRequest resolve(
                String owner,
                String askId,
                AskUserStatus status,
                String answers,
                Instant at,
                long version) {
            current =
                    new AskUserRequest(
                            current.getOwnerKey(),
                            current.getSessionId(),
                            current.getTurnId(),
                            current.getAskUserId(),
                            current.getReplyId(),
                            current.getToolCallId(),
                            current.getQuestionsJson(),
                            answers,
                            status,
                            current.getCreatedAt(),
                            current.getExpiresAt(),
                            at,
                            version + 1);
            return current;
        }
    }
}
