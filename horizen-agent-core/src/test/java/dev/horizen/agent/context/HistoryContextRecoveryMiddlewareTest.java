package dev.horizen.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.execution.session.AgentSession;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.MessageRole;
import dev.horizen.agent.execution.session.MessageStatus;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.StartTurnResult;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class HistoryContextRecoveryMiddlewareTest {
    @Test
    void reconstructsMissingRedisStateFromFinalHistoryThenPersistsItNormally() {
        InMemoryAgentStateStore redis = new InMemoryAgentStateStore();
        HistoryRecoveringAgentStateStore stateStore =
                new HistoryRecoveringAgentStateStore(
                        redis,
                        new FixedHistory(
                                List.of(
                                        message("1", MessageRole.USER, "old question"),
                                        message("2", MessageRole.ASSISTANT, "old answer"),
                                        message("3", MessageRole.USER, "new question"))));
        AtomicReference<List<Msg>> modelInput = new AtomicReference<>();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("history-recovery-test")
                        .sysPrompt("Answer briefly.")
                        .model(new EchoModel(modelInput))
                        .stateStore(stateStore)
                        .middleware(new HistoryContextRecoveryMiddleware(stateStore))
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("session")
                        .put(HistoryRecoveryScope.class, new HistoryRecoveryScope(true))
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner", "session", "turn-current"))
                        .build();

        agent.call(new UserMessage("new question"), context).block();

        assertEquals(
                List.of("old question", "old answer", "new question"),
                modelInput.get().stream()
                        .filter(msg -> msg.getRole() != MsgRole.SYSTEM)
                        .map(Msg::getTextContent)
                        .toList());
        assertTrue(modelInput.get().stream().anyMatch(msg -> msg.getRole() == MsgRole.SYSTEM));
        AgentState saved =
                redis.get("owner", "session", "agent_state", AgentState.class).orElseThrow();
        assertTrue(
                saved.getContext().stream()
                        .anyMatch(msg -> "old answer".equals(msg.getTextContent())));
    }

    @Test
    void doesNotRecoverDisplayHistoryDuringAResumeTurn() {
        InMemoryAgentStateStore redis = new InMemoryAgentStateStore();
        HistoryRecoveringAgentStateStore stateStore =
                new HistoryRecoveringAgentStateStore(
                        redis,
                        new FixedHistory(
                                List.of(message("1", MessageRole.USER, "must not restore"))));
        AtomicReference<List<Msg>> modelInput = new AtomicReference<>();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("history-recovery-resume-test")
                        .sysPrompt("Answer briefly.")
                        .model(new EchoModel(modelInput))
                        .stateStore(stateStore)
                        .middleware(new HistoryContextRecoveryMiddleware(stateStore))
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("session")
                        .put(HistoryRecoveryScope.class, new HistoryRecoveryScope(false))
                        .build();

        agent.call(new UserMessage("resume input"), context).block();

        assertEquals(
                List.of("resume input"),
                modelInput.get().stream()
                        .filter(msg -> msg.getRole() != MsgRole.SYSTEM)
                        .map(Msg::getTextContent)
                        .toList());
    }

    private static ConversationMessage message(String id, MessageRole role, String content) {
        return new ConversationMessage(
                "owner",
                "session",
                "turn",
                id,
                role,
                MessageStatus.FINAL,
                content,
                Long.parseLong(id),
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static final class EchoModel extends ChatModelBase {
        private final AtomicReference<List<Msg>> seen;

        private EchoModel(AtomicReference<List<Msg>> seen) {
            this.seen = seen;
        }

        @Override
        public String getModelName() {
            return "history-recovery-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            seen.set(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    private static final class FixedHistory implements SessionTurnStore {
        private final List<ConversationMessage> messages;

        private FixedHistory(List<ConversationMessage> messages) {
            this.messages = messages;
        }

        @Override
        public List<ConversationMessage> listFinalMessages(String ownerKey, String sessionId) {
            return messages;
        }

        @Override
        public StartTurnResult startTurn(StartTurnCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransitionTurnResult transitionTurn(TransitionTurnCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean renewLease(
                String ownerKey,
                String turnId,
                String executorId,
                Instant leaseExpiresAt,
                Instant updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentSession> findSession(String ownerKey, String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<AgentTurn> findTurn(String ownerKey, String turnId) {
            return Optional.empty();
        }

        @Override
        public Optional<AgentTurn> findLatestTurn(String ownerKey, String sessionId) {
            return Optional.empty();
        }

        @Override
        public List<AgentSession> listSessions(String ownerKey, int limit, int offset) {
            return List.of();
        }

        @Override
        public boolean renameSession(String ownerKey, String sessionId, String title, Instant now) {
            return false;
        }

        @Override
        public boolean setSessionPinned(
                String ownerKey, String sessionId, boolean pinned, Instant now) {
            return false;
        }

        @Override
        public boolean archiveSession(String ownerKey, String sessionId, Instant now) {
            return false;
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
}
