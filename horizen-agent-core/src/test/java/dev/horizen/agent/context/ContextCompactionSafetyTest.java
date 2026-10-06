package dev.horizen.agent.context;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.ToolApprovalDecision;
import dev.horizen.agent.runtime.api.ToolApprovalRequest;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.*;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证实际 Harness 压缩和持久化状态，不重复实现其算法。
 */
class ContextCompactionSafetyTest {
    @TempDir
    Path workspace;

    @Test
    void failedSummaryContinuesWithOriginalHistoryAndPersistsIt() {
        verifyFallback("error");
    }

    @Test
    void emptySummaryContinuesWithOriginalHistoryAndPersistsIt() {
        verifyFallback("empty");
    }

    private void verifyFallback(String mode) {
        var store = new InMemoryAgentStateStore();
        List<Msg> history = history("old-fact", 12);
        seed(store, "owner", history);
        var primary = new CapturingModel(false);
        var summary = new SummaryModel(mode);
        try (var runtime = runtime(store, primary, summary, false, false)) {
            List<AgentRuntimeEvent> events = call(runtime, "owner", "new-question");
            assertEquals(1, count(events, AgentRuntimeEvent.Type.CONTEXT_COMPACTION_FAILED));
            assertEquals(0, count(events, AgentRuntimeEvent.Type.CONTEXT_COMPACTED));
            assertEquals(1, count(events, AgentRuntimeEvent.Type.TURN_COMPLETED));
            assertEquals(history, conversation(primary.seen).subList(0, history.size()));
            assertFalse(primary.seen.stream().anyMatch(CompactionSummary::failed));
        }
        AgentState saved = state(store, "owner");
        assertEquals(history, saved.getContext().subList(0, history.size()));
        assertEquals(1, summary.calls);
    }

    @Test
    void emptySummaryAbortsWithoutPersistingItsPlaceholderWhenConfigured() {
        var store = new InMemoryAgentStateStore();
        var history = history("must-retain", 8);
        seed(store, "owner", history);
        var primary = new CapturingModel(false);
        try (var runtime = runtime(store, primary, new SummaryModel("empty"), true, false)) {
            var events = call(runtime, "owner", "continue");
            assertEquals(1, count(events, AgentRuntimeEvent.Type.CONTEXT_COMPACTION_FAILED));
            assertEquals(1, count(events, AgentRuntimeEvent.Type.TURN_FAILED));
            assertEquals(0, primary.calls);
        }
        assertEquals(history, state(store, "owner").getContext().subList(0, history.size()));
    }

    @Test
    void protectedHeadAndTailKeepMultipleToolResultsWithTheirCalls() {
        var store = new InMemoryAgentStateStore();
        Msg calls =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("inspect")
                                                .input(Map.of("id", 1))
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("call-2")
                                                .name("inspect")
                                                .input(Map.of("id", 2))
                                                .build()))
                        .build();
        Msg results =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        new ToolResultBlock(
                                                "call-1",
                                                "inspect",
                                                List.of(TextBlock.builder().text("one").build()),
                                                Map.of()),
                                        new ToolResultBlock(
                                                "call-2",
                                                "inspect",
                                                List.of(TextBlock.builder().text("two").build()),
                                                Map.of())))
                        .build();
        var history = new ArrayList<Msg>();
        history.add(new UserMessage("hard-constraint"));
        history.add(new AssistantMessage("ack"));
        history.add(calls);
        history.add(results);
        history.addAll(history("older-work", 10));
        // 使用相同结构但不同 ID，使截断点落在两个结果之间时仍必须保留二者。
        history.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("tail-1")
                                                .name("inspect")
                                                .input(Map.of())
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("tail-2")
                                                .name("inspect")
                                                .input(Map.of())
                                                .build()))
                        .build());
        for (String id : List.of("tail-1", "tail-2")) {
            history.add(
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    new ToolResultBlock(
                                            id,
                                            "inspect",
                                            List.of(TextBlock.builder().text(id).build()),
                                            Map.of()))
                            .build());
        }
        seed(store, "owner", history);
        var primary = new CapturingModel(false);
        try (var runtime = runtime(store, primary, new SummaryModel("ok"), false, false)) {
            assertEquals(
                    1,
                    count(
                            call(runtime, "owner", "continue"),
                            AgentRuntimeEvent.Type.CONTEXT_COMPACTED));
        }
        assertEquals(history.subList(0, 4), conversation(primary.seen).subList(0, 4));
        assertCompleteToolPairs(primary.seen);
        assertCompleteToolPairs(state(store, "owner").getContext());
        assertTrue(
                primary.seen.stream()
                        .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                        .anyMatch(tool -> "tail-1".equals(tool.getId())));
    }

    @Test
    void rollingSummarySurvivesRestartAndStaysOwnerScoped() {
        Path directory = workspace.resolve("state");
        var firstStore = new JsonFileAgentStateStore(directory);
        seed(firstStore, "owner-a", history("fact-alpha", 20));
        seed(firstStore, "owner-b", history("fact-beta", 20));
        var summary = new SummaryModel("ok");
        try (var runtime = runtime(firstStore, new CapturingModel(false), summary, false, false)) {
            call(runtime, "owner-a", "continue-a");
            call(runtime, "owner-b", "continue-b");
        }
        String originalSummary =
                state(firstStore, "owner-a").getContext().stream()
                        .filter(m -> ConversationCompactor.SUMMARY_MSG_NAME.equals(m.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getTextContent();
        var restarted = new JsonFileAgentStateStore(directory);
        var primary = new CapturingModel(false);
        var secondSummary = new SummaryModel("ok");
        try (var runtime = runtime(restarted, primary, secondSummary, false, false)) {
            for (int i = 0; i < 12; i++) call(runtime, "owner-a", "followup-" + i);
        }
        assertTrue(secondSummary.prompts.stream().anyMatch(p -> p.contains(originalSummary)));
        assertTrue(secondSummary.calls > 1);
        assertEquals("fact-alpha-0", conversation(primary.seen).get(0).getTextContent());
        assertFalse(primary.seen.stream().anyMatch(m -> m.getTextContent().contains("fact-beta")));
        assertEquals(
                "fact-beta-0", state(restarted, "owner-b").getContext().get(0).getTextContent());
        assertEquals(
                1,
                primary.seen.stream()
                        .filter(m -> ConversationCompactor.SUMMARY_MSG_NAME.equals(m.getName()))
                        .count());
    }

    @Test
    void emergencyRecoveryRejectsEmptySummaryWithoutRetryingOrLosingHistory() {
        var store = new InMemoryAgentStateStore();
        var history = history("overflow-original", 12);
        seed(store, "owner", history);
        var primary = new CapturingModel(true);
        var summary = new SummaryModel("empty");
        try (var runtime = runtime(store, primary, summary, false, true)) {
            var events = call(runtime, "owner", "continue");
            assertEquals(1, count(events, AgentRuntimeEvent.Type.CONTEXT_COMPACTION_FAILED));
            assertEquals(1, count(events, AgentRuntimeEvent.Type.TURN_FAILED));
            assertEquals(1, primary.calls);
            assertEquals(1, summary.calls);
        }
        assertEquals(history, state(store, "owner").getContext().subList(0, history.size()));
    }

    @Test
    void compactionAndApprovalResumeExecuteExactlyTheFrozenArguments() {
        var store = new InMemoryAgentStateStore();
        var permission =
                PermissionContextState.builder()
                        .addAskRule(
                                "draft_action",
                                new PermissionRule(
                                        "draft_action", null, PermissionBehavior.ASK, "test"))
                        .build();
        store.save(
                "owner",
                "same-session",
                "agent_state",
                AgentState.builder()
                        .userId("owner")
                        .sessionId("same-session")
                        .context(history("approval-history", 16))
                        .permissionContext(permission)
                        .build());
        Map<String, Object> frozen = Map.of("value", "approved-draft-only");
        var executed = new AtomicReference<Map<String, Object>>();
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("draft_action")
                                .description("Synthetic draft action")
                                .readOnly(false)
                                .inputSchema(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of("value", Map.of("type", "string"))))) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        assertTrue(
                                executed.compareAndSet(null, Map.copyOf(param.getInput())),
                                "Repeated dispatch");
                        return Mono.just(ToolResultBlock.text("saved"));
                    }
                });
        var primary =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "approval-compaction-primary";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (messages.stream()
                                .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)))
                            return text("saved");
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("frozen-call")
                                                                .name("draft_action")
                                                                .input(frozen)
                                                                .content(
                                                                        "{\"value\":\"approved-draft-only\"}")
                                                                .build()))
                                        .build());
                    }
                };
        var summary = new SummaryModel("ok");
        var agent =
                builder(store, primary, summary, false, false)
                        .toolkit(toolkit)
                        .permissionContext(permission)
                        .build();
        try (var runtime = new HarnessAgentRuntime(agent)) {
            var paused =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .turnId("approval-turn")
                                            .ownerKey("owner")
                                            .sessionId("same-session")
                                            .message("prepare draft")
                                            .build())
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertEquals(1, count(paused, AgentRuntimeEvent.Type.CONTEXT_COMPACTED));
            var required =
                    paused.stream()
                            .filter(e -> e.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError(paused.toString()));
            var pending = (ToolApprovalRequest) ((List<?>) required.getDetails()).get(0);
            assertEquals(frozen, pending.getInput());
            assertNull(executed.get());
            var resumed =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .turnId("approval-turn")
                                            .ownerKey("owner")
                                            .sessionId("same-session")
                                            .message("approved")
                                            .approvalDecisions(
                                                    List.of(
                                                            new ToolApprovalDecision(
                                                                    pending.getToolCallId(),
                                                                    pending.getToolName(),
                                                                    pending.getContent(),
                                                                    pending.getInput(),
                                                                    true)))
                                            .build())
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertEquals(1, count(resumed, AgentRuntimeEvent.Type.TURN_COMPLETED));
            assertEquals(frozen, executed.get());
        }
    }

    private HarnessAgentRuntime runtime(
            AgentStateStore store,
            CapturingModel primary,
            SummaryModel summary,
            boolean abort,
            boolean emergency) {
        return new HarnessAgentRuntime(builder(store, primary, summary, abort, emergency).build());
    }

    private HarnessAgent.Builder builder(
            AgentStateStore store,
            ChatModelBase primary,
            SummaryModel summary,
            boolean abort,
            boolean emergency) {
        CompactionConfig config = config(summary, emergency ? 1_000_000 : 6);
        var builder =
                HarnessAgent.builder()
                        .name("compaction-safety")
                        .sysPrompt("Follow the user's constraints.")
                        .model(primary)
                        .stateStore(store)
                        .workspace(workspace)
                        .middleware(ContextCompactionTelemetry.beforeCompaction())
                        .middleware(ProtectedHeadContextMiddleware.beforeCompaction(3))
                        .middleware(ProtectedHeadContextMiddleware.afterCompaction())
                        .middleware(CompactionPerTurnLimitMiddleware.beforeCompaction(1))
                        .middleware(CompactionPerTurnLimitMiddleware.afterCompaction())
                        .middleware(ContextCompactionTelemetry.afterCompaction(abort))
                        .compaction(config)
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool();
        if (emergency)
            builder.middleware(
                    new ContextOverflowRecoveryMiddleware(summary, config(summary, 1), 3));
        return builder;
    }

    private static CompactionConfig config(Model summary, int trigger) {
        return CompactionConfig.builder()
                .model(summary)
                .triggerMessages(trigger)
                .triggerTokens(1_000_000)
                .keepMessages(2)
                .keepTokens(0)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .prune(null)
                .build();
    }

    private static List<Msg> history(String prefix, int count) {
        var messages = new ArrayList<Msg>();
        for (int i = 0; i < count; i++)
            messages.add(
                    i % 2 == 0
                            ? new UserMessage(prefix + "-" + i)
                            : new AssistantMessage(prefix + "-" + i));
        return messages;
    }

    private static void seed(AgentStateStore store, String owner, List<Msg> messages) {
        store.save(
                owner,
                "same-session",
                "agent_state",
                AgentState.builder()
                        .userId(owner)
                        .sessionId("same-session")
                        .context(messages)
                        .build());
    }

    private static AgentState state(AgentStateStore store, String owner) {
        return store.get(owner, "same-session", "agent_state", AgentState.class).orElseThrow();
    }

    private static List<AgentRuntimeEvent> call(
            HarnessAgentRuntime runtime, String owner, String text) {
        return runtime.stream(
                        AgentTurnRequest.builder()
                                .ownerKey(owner)
                                .sessionId("same-session")
                                .turnId(UUID.randomUUID().toString())
                                .message(text)
                                .build())
                .onErrorResume(error -> Flux.empty())
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    private static long count(List<AgentRuntimeEvent> events, AgentRuntimeEvent.Type type) {
        return events.stream().filter(e -> e.getType() == type).count();
    }

    private static List<Msg> conversation(List<Msg> messages) {
        return messages.stream().filter(m -> m.getRole() != MsgRole.SYSTEM).toList();
    }

    private static void assertCompleteToolPairs(List<Msg> messages) {
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (Msg message : messages) {
            message.getContentBlocks(ToolUseBlock.class).forEach(tool -> calls.add(tool.getId()));
            message.getContentBlocks(ToolResultBlock.class)
                    .forEach(
                            tool -> {
                                assertTrue(
                                        calls.contains(tool.getId()),
                                        "Orphan result: " + tool.getId());
                                results.add(tool.getId());
                            });
        }
        assertEquals(calls, results);
    }

    private static final class CapturingModel extends ChatModelBase {
        private final boolean overflow;
        private int calls;
        private List<Msg> seen;

        private CapturingModel(boolean overflow) {
            this.overflow = overflow;
        }

        @Override
        public String getModelName() {
            return "compaction-safety-primary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls++;
            seen = List.copyOf(messages);
            return overflow
                    ? Flux.error(new IllegalStateException("context_length_exceeded"))
                    : text("ok");
        }
    }

    private static final class SummaryModel extends ChatModelBase {
        private final String mode;
        private int calls;
        private final List<String> prompts = new ArrayList<>();

        private SummaryModel(String mode) {
            this.mode = mode;
        }

        @Override
        public String getModelName() {
            return "compaction-safety-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls++;
            prompts.add(messages.get(0).getTextContent());
            if ("error".equals(mode))
                return Flux.error(new IllegalStateException("summary unavailable"));
            if ("empty".equals(mode)) return Flux.empty();
            return text("synthetic-summary-" + calls);
        }
    }

    private static Flux<ChatResponse> text(String value) {
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(value).build()))
                        .build());
    }
}
