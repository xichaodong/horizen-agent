package dev.horizen.agent.tools.session;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactSource;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;
import dev.horizen.agent.execution.session.AgentSession;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.MessageRole;
import dev.horizen.agent.execution.session.MessageStatus;
import dev.horizen.agent.execution.session.SessionHistoryEntry;
import dev.horizen.agent.execution.session.SessionHistoryPage;
import dev.horizen.agent.execution.session.SessionHistoryQuery;
import dev.horizen.agent.execution.session.SessionHistoryRepository;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.StartTurnResult;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

class CloudSessionSearchToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void agentLoopReceivesRecallResultAndRuntimeInjectsScope(@TempDir Path workspace) {
        RecallHistory history = new RecallHistory();
        history.reply =
                query ->
                        Optional.of(
                                new SessionHistoryPage(
                                        null,
                                        null,
                                        null,
                                        List.of(entry("past", "message", "Excel旧分析按华东过滤", 0, 14)),
                                        false,
                                        1));
        var model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "synthetic-recall";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        var recalled =
                                messages.stream()
                                        .flatMap(
                                                message ->
                                                        message
                                                                .getContentBlocks(
                                                                        ToolResultBlock.class)
                                                                .stream())
                                        .findFirst();
                        ContentBlock block;
                        if (recalled.isEmpty()) {
                            block =
                                    ToolUseBlock.builder()
                                            .id("recall-call")
                                            .name("session_search")
                                            .input(Map.of("query", "Excel"))
                                            .build();
                        } else {
                            assertTrue(output(recalled.get()).contains("Excel旧分析按华东过滤"));
                            block = TextBlock.builder().text("按过去会话的华东口径继续分析").build();
                        }
                        return Flux.just(ChatResponse.builder().content(List.of(block)).build());
                    }
                };
        var agent =
                HarnessAgent.builder()
                        .name("recall-loop")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        agent.getToolkit().removeTool("session_search");
        agent.getToolkit().registerAgentTool(new CloudSessionSearchTool(history));
        try (var runtime = new HarnessAgentRuntime(agent)) {
            var events =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .ownerKey("owner-a")
                                            .sessionId("session-a")
                                            .turnId("turn-recall")
                                            .message("继续上次Excel分析")
                                            .build())
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertEquals(
                    "按过去会话的华东口径继续分析",
                    events.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .findFirst()
                            .orElseThrow()
                            .getText());
        }
        assertEquals("owner-a", history.seen.getOwnerKey());
        assertEquals("session-a", history.seen.getCurrentSessionId());
    }

    @Test
    void artifactPagesRemainBoundedAndAdvancePastExpiredReferences() throws Exception {
        var artifactStore = new Files();
        var tool = new CloudSessionSearchTool(new RecallHistory(), artifactStore);
        var first = JSON.readTree(output(call(tool, Map.of(), context())));
        assertEquals(10, first.path("next_artifact_offset").asInt());
        assertEquals(9, first.path("artifacts").size());
        assertEquals(11, artifactStore.lastLimit);
        var second = JSON.readTree(output(call(tool, Map.of("artifact_offset", 10), context())));
        assertEquals(2, second.path("artifacts").size());
        assertFalse(second.path("artifacts_has_more").asBoolean());
        assertEquals(10, artifactStore.lastOffset);
        var denied = new RecallHistory();
        denied.reply = query -> Optional.empty();
        int prior = artifactStore.calls;
        assertEquals(
                ToolResultState.ERROR,
                call(
                        new CloudSessionSearchTool(denied, artifactStore),
                        Map.of("session_id", "foreign"),
                        context())
                        .getState());
        assertEquals(prior, artifactStore.calls);
    }

    private static final class Files implements ArtifactStore {
        private int lastLimit, lastOffset, calls;

        @Override
        public List<Artifact> listForSession(String owner, String session, int limit, int offset) {
            lastLimit = limit;
            lastOffset = offset;
            calls++;
            return IntStream.range(offset, Math.min(12, offset + limit))
                    .mapToObj(
                            i ->
                                    new Artifact(
                                            "file-" + i,
                                            owner,
                                            ArtifactKind.FILE,
                                            ArtifactState.READY,
                                            "book-" + i,
                                            "application/test",
                                            "objects/file-" + i,
                                            1L,
                                            null,
                                            null,
                                            ArtifactSource.USER,
                                            null,
                                            i == 0 ? Instant.EPOCH : null,
                                            Instant.EPOCH,
                                            Instant.EPOCH,
                                            null,
                                            0))
                    .toList();
        }

        @Override
        public Artifact create(Artifact a) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Artifact> find(String owner, String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact update(Artifact a, long version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addReference(ArtifactReference ref) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ArtifactReference> listReferences(String owner, String id) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void queryUsesTrustedScopeAndReturnsHistoricalSnippetWithProvenance() throws Exception {
        RecallHistory history = new RecallHistory();
        history.reply =
                query ->
                        Optional.of(
                                new SessionHistoryPage(
                                        null,
                                        null,
                                        null,
                                        List.of(entry("past", "message", "Excel按华东过滤", 0, 12)),
                                        false,
                                        1));
        var result = call(new CloudSessionSearchTool(history), Map.of("query", "Excel"), context());
        var payload = JSON.readTree(output(result));
        assertEquals("search", payload.path("mode").asText());
        assertEquals("past", payload.path("results").get(0).path("session_id").asText());
        assertEquals("message", payload.path("results").get(0).path("message_id").asText());
        assertFalse(payload.path("results").get(0).path("created_at").asText().isBlank());
        assertEquals("owner-a", history.seen.getOwnerKey());
        assertEquals("session-a", history.seen.getCurrentSessionId());
        assertNull(history.seen.getTargetSessionId());
        assertEquals(5, history.seen.getLimit());
    }

    @Test
    void selectedSessionIsReadOnlyAfterRepositoryAuthorization() throws Exception {
        RecallHistory history = new RecallHistory();
        history.reply =
                query ->
                        query.getTargetSessionId().equals("past")
                                ? Optional.of(
                                new SessionHistoryPage(
                                        "past",
                                        "过去分析",
                                        1L,
                                        List.of(
                                                entry(
                                                        "past",
                                                        "message",
                                                        "past result",
                                                        0,
                                                        11)),
                                        false,
                                        1))
                                : Optional.empty();
        var tool = new CloudSessionSearchTool(history);
        var read = JSON.readTree(output(call(tool, Map.of("session_id", "past"), context())));
        assertEquals("past", read.path("session_id").asText());
        assertEquals("past result", read.path("messages").get(0).path("content").asText());
        assertEquals(
                ToolResultState.ERROR,
                call(tool, Map.of("session_id", "foreign"), context()).getState());
        assertEquals(
                ToolResultState.ERROR,
                call(tool, Map.of("session_id", "missing"), context()).getState());
    }

    @Test
    void childUsesParentSessionButCannotChangeOwner() {
        RecallHistory history = new RecallHistory();
        var child =
                RuntimeContext.builder()
                        .userId("owner-a")
                        .sessionId("child")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner-a", "session-a", "turn"))
                        .build();
        call(new CloudSessionSearchTool(history), Map.of("query", "Excel"), child);
        assertEquals("session-a", history.seen.getCurrentSessionId());
        var invalid =
                RuntimeContext.builder()
                        .userId("owner-b")
                        .sessionId("child")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner-a", "session-a", "turn"))
                        .build();
        assertEquals(
                ToolResultState.ERROR,
                call(new CloudSessionSearchTool(history), Map.of(), invalid).getState());
    }

    @Test
    void identityInjectionAndMalformedPaginationAreRejectedBeforeQuery() {
        RecallHistory history = new RecallHistory();
        var tool = new CloudSessionSearchTool(history);
        for (var input :
                List.<Map<String, Object>>of(
                        Map.of("ownerKey", "owner-b"),
                        Map.of("projectId", 9),
                        Map.of("offset", Integer.MAX_VALUE),
                        Map.of("limit", 1.5),
                        Map.of("query", " "),
                        Map.of("query", "x".repeat(201)),
                        Map.of("query", "Excel", "limit", 100),
                        Map.of("text_offset", 4),
                        Map.of("limit", 1e-100),
                        Map.of("offset", Double.NaN),
                        Map.of("artifact_offset", 10, "query", "Excel"),
                        Map.of("message_id", "message", "query", "Excel"))) {
            assertEquals(
                    ToolResultState.ERROR,
                    call(tool, input, context()).getState(),
                    input.toString());
        }
        assertNull(history.seen);
    }

    @Test
    void queryFailureIsNotEmptyHistoryAndPrivateExceptionDetailIsNotReturned() {
        RecallHistory history = new RecallHistory();
        history.reply =
                query -> {
                    throw new IllegalStateException("synthetic private database detail");
                };
        var result = call(new CloudSessionSearchTool(history), Map.of("query", "Excel"), context());
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(output(result).contains("session_history_unavailable"));
        assertFalse(output(result).contains("private"));
    }

    @Test
    void legacyStorageStillReadsCurrentButDoesNotPretendToSearchHistory() {
        var result =
                call(
                        new CloudSessionSearchTool(new History()),
                        Map.of("query", "Excel"),
                        context());
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(output(result).contains("history_search_not_configured"));
    }

    @Test
    void longMessageChunkMetadataAndTotalOutputBudgetAreExplicit() throws Exception {
        RecallHistory history = new RecallHistory();
        history.reply =
                query ->
                        Optional.of(
                                new SessionHistoryPage(
                                        "session-a",
                                        null,
                                        100L,
                                        IntStream.range(0, 100)
                                                .mapToObj(
                                                        i ->
                                                                entry(
                                                                        "session-a",
                                                                        "message-" + i,
                                                                        "中".repeat(4000),
                                                                        0,
                                                                        9000))
                                                .toList(),
                                        false,
                                        100));
        String result =
                output(call(new CloudSessionSearchTool(history), Map.of("limit", 100), context()));
        var payload = JSON.readTree(result);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 64 * 1024);
        assertTrue(payload.path("count").asInt() > 0 && payload.path("count").asInt() < 100);
        assertTrue(payload.path("has_more").asBoolean());
        assertEquals(payload.path("count").asInt(), payload.path("next_offset").asInt());
        assertTrue(payload.path("messages").get(0).path("content_truncated").asBoolean());
        assertEquals(4000, payload.path("messages").get(0).path("next_content_offset").asInt());
    }

    private static String output(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("owner-a").sessionId("session-a").build();
    }

    private static ToolResultBlock call(
            CloudSessionSearchTool tool, Map<String, Object> input, RuntimeContext context) {
        return tool.callAsync(
                        ToolCallParam.builder()
                                .runtimeContext(context)
                                .input(input)
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("call")
                                                .name("session_search")
                                                .input(input)
                                                .content("{}")
                                                .build())
                                .build())
                .block(Duration.ofSeconds(5));
    }

    private static SessionHistoryEntry entry(
            String session, String id, String body, int offset, long length) {
        return new SessionHistoryEntry(
                session,
                "历史分析",
                "turn",
                id,
                MessageRole.ASSISTANT,
                1,
                Instant.EPOCH,
                body,
                offset,
                length);
    }

    private static final class RecallHistory extends History implements SessionHistoryRepository {
        private SessionHistoryQuery seen;
        private Function<SessionHistoryQuery, Optional<SessionHistoryPage>> reply =
                query ->
                        Optional.of(
                                new SessionHistoryPage(
                                        query.getTargetSessionId(), null, 0L, List.of(), false, 0));

        @Override
        public Optional<SessionHistoryPage> queryHistory(SessionHistoryQuery query) {
            seen = query;
            return reply.apply(query);
        }
    }

    @Test
    void returnsOnlyTheRuntimeOwnersCurrentSessionWithoutKeywordFiltering() {
        CloudSessionSearchTool tool = new CloudSessionSearchTool(new History());
        var result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder()
                                                        .userId("owner-a")
                                                        .sessionId("session-a")
                                                        .build())
                                        .input(Map.of())
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("call")
                                                        .name("session_search")
                                                        .input(Map.of())
                                                        .content("{}")
                                                        .build())
                                        .build())
                        .block();
        String text = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(text.contains("report conclusion"));
        assertTrue(text.contains("another session message"));
    }

    private static class History implements SessionTurnStore {
        @Override
        public List<ConversationMessage> listFinalMessages(String owner, String session) {
            if (!"owner-a".equals(owner) || !"session-a".equals(session)) return List.of();
            return List.of(
                    message("message-1", "report conclusion"),
                    message("message-2", "another session message"));
        }

        private static ConversationMessage message(String id, String content) {
            return new ConversationMessage(
                    "owner-a",
                    "session-a",
                    "turn",
                    id,
                    MessageRole.ASSISTANT,
                    MessageStatus.FINAL,
                    content,
                    1,
                    Instant.EPOCH,
                    Instant.EPOCH);
        }

        @Override
        public StartTurnResult startTurn(StartTurnCommand c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransitionTurnResult transitionTurn(TransitionTurnCommand c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean renewLease(String a, String b, String c, Instant d, Instant e) {
            return false;
        }

        @Override
        public Optional<AgentSession> findSession(String a, String b) {
            return Optional.empty();
        }

        @Override
        public Optional<AgentTurn> findTurn(String a, String b) {
            return Optional.empty();
        }

        @Override
        public Optional<AgentTurn> findLatestTurn(String a, String b) {
            return Optional.empty();
        }

        @Override
        public List<AgentSession> listSessions(String a, int b, int c) {
            return List.of();
        }

        @Override
        public boolean renameSession(String a, String b, String c, Instant d) {
            return false;
        }

        @Override
        public boolean setSessionPinned(String a, String b, boolean c, Instant d) {
            return false;
        }

        @Override
        public boolean archiveSession(String a, String b, Instant c) {
            return false;
        }

        @Override
        public List<AgentTurn> findExpiredLeases(Instant a, int b) {
            return List.of();
        }

        @Override
        public List<AgentTurn> findOverdueTurns(Instant a, int b) {
            return List.of();
        }
    }
}
