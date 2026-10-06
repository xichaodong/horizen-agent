package dev.horizen.agent.adapter.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.adapter.agentscope.askuser.AskUserTool;
import dev.horizen.agent.context.CompactionPerTurnLimitMiddleware;
import dev.horizen.agent.context.ContextCompactionTelemetry;
import dev.horizen.agent.context.ContextOverflowRecoveryMiddleware;
import dev.horizen.agent.context.ProtectedHeadContextMiddleware;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.runtime.api.AgentInputAttachment;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.AskUserDecision;
import dev.horizen.agent.runtime.api.SessionExecutionState;
import dev.horizen.agent.runtime.api.SessionTurnBusyException;
import dev.horizen.agent.runtime.api.ToolApprovalDecision;
import dev.horizen.agent.runtime.api.ToolApprovalRequest;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;

class HarnessAgentRuntimeTest {
    @TempDir
    Path workspace;

    @Test
    void iterationLimitProducesVisibleFailureAndRetainsPartialSummary() {
        ChatModelBase model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "synthetic-limit";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (tools == null || tools.isEmpty())
                            return text("synthetic partial summary");
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.<ContentBlock>of(
                                                        ToolUseBlock.builder()
                                                                .id("read-call")
                                                                .name("read_file")
                                                                .input(
                                                                        Map.of(
                                                                                "path",
                                                                                "missing.txt"))
                                                                .build()))
                                        .build());
                    }
                };
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("iteration-limit-test")
                        .model(model)
                        .workspace(workspace)
                        .maxIters(1)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            var events =
                    runtime.stream(
                                    request(
                                            "turn-limit",
                                            "alice",
                                            "limit-session",
                                            "synthetic task"))
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertTrue(
                    events.stream()
                            .noneMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED));
            var failure =
                    events.stream()
                            .filter(event -> event.getType() == AgentRuntimeEvent.Type.TURN_FAILED)
                            .findFirst()
                            .orElseThrow();
            assertTrue(failure.getText().contains("执行次数限制"));
            assertTrue(failure.getText().contains("synthetic partial summary"));
            assertEquals(
                    "MAX_ITERATIONS_REACHED", ((Map<?, ?>) failure.getDetails()).get("errorCode"));
            assertEquals(
                    TurnStatus.FAILED,
                    runtime.sessionExecution("alice", "limit-session").orElseThrow().getStatus());
        }
    }

    @Test
    void missingResultProducesFailureEventInsteadOfOnlyUpdatingState() {
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("missing-result-test")
                        .model(new ConversationModel())
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Flux<AgentEvent> onAgent(
                                            Agent agent,
                                            RuntimeContext context,
                                            AgentInput input,
                                            Function<AgentInput, Flux<AgentEvent>> next) {
                                        return Flux.empty();
                                    }
                                })
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            var events =
                    runtime.stream(
                                    request(
                                            "turn-empty",
                                            "alice",
                                            "empty-session",
                                            "synthetic task"))
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertEquals(
                    AgentRuntimeEvent.Type.TURN_FAILED, events.get(events.size() - 1).getType());
            assertEquals(
                    "MISSING_TERMINAL_RESULT",
                    ((Map<?, ?>) events.get(events.size() - 1).getDetails()).get("errorCode"));
        }
    }

    @Test
    void preservesSubagentExecutionTreeWithoutCompletingParentTurnEarly() {
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("execution-tree-test")
                        .sysPrompt("Delegate the requested child task.")
                        .model(new DelegatingModel())
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .subagent(
                                SubagentDeclaration.builder()
                                        .name("worker")
                                        .description("Deterministic execution-tree test worker.")
                                        .inlineAgentsBody("Return the delegated child result.")
                                        .steps(2)
                                        .build())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableShellTool()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            List<AgentRuntimeEvent> events =
                    runtime.stream(request("turn-tree", "owner", "session-tree", "delegate"))
                            .collectList()
                            .block(Duration.ofSeconds(5));

            assertEquals(
                    1,
                    events.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .count());
            assertEquals(
                    "parent-result",
                    events.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .findFirst()
                            .orElseThrow()
                            .getText());
            AgentRuntimeEvent started =
                    events.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.SUBAGENT_STARTED)
                            .findFirst()
                            .orElseThrow();
            assertEquals("session-tree/worker", started.getSource());
            assertEquals("session-tree", started.getParentSessionId());
            assertEquals("worker", started.getAgentId());
            assertEquals(1, started.getDepth());
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event.getType() == AgentRuntimeEvent.Type.TEXT_DELTA
                                                    && "child-result".equals(event.getText())
                                                    && started.getSource()
                                                    .equals(event.getSource())),
                    events.toString());
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type
                                                    .SUBAGENT_COMPLETED
                                                    && started.getSource()
                                                    .equals(event.getSource())));
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event.getType() == AgentRuntimeEvent.Type.MODEL_STARTED
                                                    && started.getSource()
                                                    .equals(event.getSource())));
        }
    }

    @Test
    void sendsCurrentTurnImageAttachmentsAsImageBlocks() {
        ImageAwareModel model = new ImageAwareModel();
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("multimodal-input-test")
                        .sysPrompt("Inspect the image.")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(stateStore)
                        .middleware(new MultimodalInputMiddleware())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        AgentTurnRequest request =
                AgentTurnRequest.builder()
                        .turnId("turn-image")
                        .ownerKey("owner")
                        .sessionId("session")
                        .message("分析这张图片，引用 art-image")
                        .attachments(
                                List.of(
                                        new AgentInputAttachment(
                                                "art-image",
                                                "image.png",
                                                "image/png",
                                                "https://bos.example.test/owner/art-image?signature=test")))
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            assertEquals(
                    "image-received",
                    finalReply(runtime, request).block(Duration.ofSeconds(5)).getText());
        }
        assertTrue(model.sawImage);
        AgentState stored =
                stateStore.get("owner", "session", "agent_state", AgentState.class).orElseThrow();
        assertTrue(
                stored.contextMutable().stream()
                        .noneMatch(message -> message.hasContentBlocks(ImageBlock.class)));
    }

    @Test
    void emitsContextCompactedWhenAgentScopeReplacesOldHistory() {
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("compaction-telemetry-test")
                        .sysPrompt("Answer briefly.")
                        .model(new CompactionModel())
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(ContextCompactionTelemetry.beforeCompaction())
                        .middleware(ContextCompactionTelemetry.afterCompaction())
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(3)
                                        .triggerTokens(1_000_000)
                                        .keepMessages(1)
                                        .keepTokens(0)
                                        .flushBeforeCompact(false)
                                        .offloadBeforeCompact(false)
                                        .prune(null)
                                        .build())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            finalReply(
                    runtime,
                    request(
                            "turn-context-1",
                            "alice",
                            "context-session",
                            "first ".repeat(500)))
                    .block(Duration.ofSeconds(5));

            List<AgentRuntimeEvent> events =
                    runtime.stream(request("turn-context-2", "alice", "context-session", "second"))
                            .collectList()
                            .block(Duration.ofSeconds(5));

            AgentRuntimeEvent compacted =
                    events.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.CONTEXT_COMPACTED)
                            .findFirst()
                            .orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) compacted.getDetails();
            assertEquals(4, details.get("messagesBefore"));
            assertEquals(3, details.get("messagesAfter"));
            assertTrue(
                    (Integer) details.get("estimatedTokensAfter")
                            < (Integer) details.get("estimatedTokensBefore"));
        }
    }

    @Test
    void preservesConfiguredHeadMessagesAcrossCompaction() {
        String foundingInstruction = "FOUNDING-INSTRUCTION-KEEP-VERBATIM";
        HeadProtectionModel model = new HeadProtectionModel(foundingInstruction);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("head-protection-test")
                        .sysPrompt("Answer briefly.")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(ContextCompactionTelemetry.beforeCompaction())
                        .middleware(ProtectedHeadContextMiddleware.beforeCompaction(1))
                        .middleware(ProtectedHeadContextMiddleware.afterCompaction())
                        .middleware(ContextCompactionTelemetry.afterCompaction())
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(2)
                                        .triggerTokens(1_000_000)
                                        .keepMessages(1)
                                        .keepTokens(0)
                                        .flushBeforeCompact(false)
                                        .offloadBeforeCompact(false)
                                        .prune(null)
                                        .build())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            finalReply(
                    runtime,
                    request("turn-head-1", "alice", "head-session", foundingInstruction))
                    .block(Duration.ofSeconds(5));
            AgentRuntimeEvent reply =
                    finalReply(runtime, request("turn-head-2", "alice", "head-session", "continue"))
                            .block(Duration.ofSeconds(5));

            assertEquals("head-preserved", reply.getText());
            assertTrue(model.summaryCalls > 0);
        }
    }

    @Test
    void recoversStreamingModelCallFromContextOverflowOnce() {
        OverflowOnceModel model = new OverflowOnceModel();
        CompactionConfig normal =
                CompactionConfig.builder()
                        .triggerMessages(100)
                        .triggerTokens(1_000_000)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .prune(null)
                        .model(model)
                        .build();
        CompactionConfig emergency =
                CompactionConfig.builder()
                        .triggerMessages(1)
                        .triggerTokens(1)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .prune(null)
                        .model(model)
                        .build();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("overflow-recovery-test")
                        .sysPrompt("Answer briefly.")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(new ContextOverflowRecoveryMiddleware(model, emergency, 1))
                        .compaction(normal)
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            finalReply(runtime, request("turn-overflow-1", "alice", "overflow-session", "first"))
                    .block(Duration.ofSeconds(5));
            List<AgentRuntimeEvent> events =
                    runtime.stream(
                                    request(
                                            "turn-overflow-2",
                                            "alice",
                                            "overflow-session",
                                            "second"))
                            .collectList()
                            .block(Duration.ofSeconds(5));

            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.CONTEXT_COMPACTED));
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type
                                                    .EXECUTION_NOTICE
                                                    && !event.getText().isBlank()));
            assertEquals(
                    "reply:second",
                    events.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .findFirst()
                            .orElseThrow()
                            .getText());
            assertEquals(1, model.summaryCalls);
            assertTrue(model.sawProtectedHeadOnRetry);
        }
    }

    @Test
    void reportsAndAbortsWhenCompressionSummaryFails() {
        FailingSummaryModel model = new FailingSummaryModel();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("summary-failure-test")
                        .sysPrompt("Answer briefly.")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(ContextCompactionTelemetry.beforeCompaction())
                        .middleware(ContextCompactionTelemetry.afterCompaction(true))
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(3)
                                        .triggerTokens(1_000_000)
                                        .keepMessages(1)
                                        .keepTokens(0)
                                        .flushBeforeCompact(false)
                                        .offloadBeforeCompact(false)
                                        .prune(null)
                                        .build())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            finalReply(
                    runtime,
                    request(
                            "turn-summary-fail-1",
                            "alice",
                            "summary-fail-session",
                            "first"))
                    .block(Duration.ofSeconds(5));
            List<AgentRuntimeEvent> events =
                    runtime.stream(
                                    request(
                                            "turn-summary-fail-2",
                                            "alice",
                                            "summary-fail-session",
                                            "second"))
                            .onErrorResume(ignored -> Flux.empty())
                            .collectList()
                            .block(Duration.ofSeconds(5));

            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type
                                                    .CONTEXT_COMPACTION_FAILED));
            assertEquals(
                    AgentRuntimeEvent.Type.TURN_FAILED, events.get(events.size() - 1).getType());
        }
    }

    @Test
    void limitsNormalCompactionToOnceAcrossToolIterationsInOneTurn() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new LoopTool());
        MultiStepCompactionModel model = new MultiStepCompactionModel();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("compaction-per-turn-test")
                        .sysPrompt("Call loop_tool twice, then answer.")
                        .model(model)
                        .toolkit(toolkit)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(ContextCompactionTelemetry.beforeCompaction())
                        .middleware(CompactionPerTurnLimitMiddleware.beforeCompaction(1))
                        .middleware(CompactionPerTurnLimitMiddleware.afterCompaction())
                        .middleware(ContextCompactionTelemetry.afterCompaction())
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(3)
                                        .triggerTokens(1_000_000)
                                        .keepMessages(1)
                                        .keepTokens(0)
                                        .flushBeforeCompact(false)
                                        .offloadBeforeCompact(false)
                                        .prune(null)
                                        .build())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            AgentRuntimeEvent reply =
                    finalReply(
                            runtime,
                            request(
                                    "turn-compact-once",
                                    "alice",
                                    "compact-once-session",
                                    "run"))
                            .block(Duration.ofSeconds(5));

            assertEquals("done", reply.getText());
            assertEquals(1, model.summaryCalls);
        }
    }

    @Test
    void isolatesConversationStateByOwnerEvenWhenSessionIdsMatch() {
        try (HarnessAgentRuntime runtime = runtime(new ConversationModel(), true)) {
            Mono.zip(
                            finalReply(
                                    runtime,
                                    request(
                                            "turn-a1",
                                            "alice",
                                            "same-session",
                                            "remember:Alice"))
                                    .subscribeOn(Schedulers.parallel()),
                            finalReply(
                                    runtime,
                                    request(
                                            "turn-b1",
                                            "bob",
                                            "same-session",
                                            "remember:Bob"))
                                    .subscribeOn(Schedulers.parallel()))
                    .block(Duration.ofSeconds(5));

            var replies =
                    Mono.zip(
                                    finalReply(
                                            runtime,
                                            request(
                                                    "turn-a2",
                                                    "alice",
                                                    "same-session",
                                                    "who am I?"))
                                            .subscribeOn(Schedulers.parallel()),
                                    finalReply(
                                            runtime,
                                            request(
                                                    "turn-b2",
                                                    "bob",
                                                    "same-session",
                                                    "who am I?"))
                                            .subscribeOn(Schedulers.parallel()))
                            .block(Duration.ofSeconds(5));

            assertEquals("Alice", replies.getT1().getText());
            assertEquals("Bob", replies.getT2().getText());
        }
    }

    @Test
    void resolvesOwnerSpecificAgentsMdOnTheSameAgentInstance() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "BASE_INSTRUCTION");
        Files.createDirectories(workspace.resolve("alice"));
        Files.createDirectories(workspace.resolve("bob"));
        Files.writeString(workspace.resolve("alice/AGENTS.md"), "ALICE_ONLY_INSTRUCTION");
        Files.writeString(workspace.resolve("bob/AGENTS.md"), "BOB_ONLY_INSTRUCTION");

        try (HarnessAgentRuntime runtime = runtime(new WorkspaceInstructionModel(), false)) {
            var replies =
                    Mono.zip(
                                    finalReply(
                                            runtime,
                                            request(
                                                    "turn-a",
                                                    "alice",
                                                    "same-session",
                                                    "instruction?"))
                                            .subscribeOn(Schedulers.parallel()),
                                    finalReply(
                                            runtime,
                                            request(
                                                    "turn-b",
                                                    "bob",
                                                    "same-session",
                                                    "instruction?"))
                                            .subscribeOn(Schedulers.parallel()))
                            .block(Duration.ofSeconds(5));

            assertEquals("ALICE_ONLY_INSTRUCTION", replies.getT1().getText());
            assertEquals("BOB_ONLY_INSTRUCTION", replies.getT2().getText());
        }
    }

    @Test
    void resolvesSameNamedSkillFromEachOwnersWorkspace() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "Use relevant skills.");
        writeSkill("alice", "Alice skill", "ALICE_SKILL_BODY");
        writeSkill("bob", "Bob skill", "BOB_SKILL_BODY");

        try (HarnessAgentRuntime runtime = runtime(new SkillLoadingModel(), false)) {
            var replies =
                    Mono.zip(
                                    finalReply(
                                            runtime,
                                            request(
                                                    "turn-skill-a",
                                                    "alice",
                                                    "same-session",
                                                    "load skill"))
                                            .subscribeOn(Schedulers.parallel()),
                                    finalReply(
                                            runtime,
                                            request(
                                                    "turn-skill-b",
                                                    "bob",
                                                    "same-session",
                                                    "load skill"))
                                            .subscribeOn(Schedulers.parallel()))
                            .block(Duration.ofSeconds(5));

            assertEquals("ALICE_SKILL_BODY", replies.getT1().getText());
            assertEquals("BOB_SKILL_BODY", replies.getT2().getText());
        }
    }

    @Test
    void restoresOwnerStateAfterRuntimeRestart() {
        Path stateDirectory = workspace.resolve("state");
        try (HarnessAgentRuntime first =
                     runtime(
                             new ConversationModel(),
                             true,
                             new JsonFileAgentStateStore(stateDirectory))) {
            finalReply(
                    first,
                    request(
                            "turn-before-restart",
                            "alice",
                            "restart-session",
                            "remember:Alice"))
                    .block(Duration.ofSeconds(5));
        }

        try (HarnessAgentRuntime restarted =
                     runtime(
                             new ConversationModel(),
                             true,
                             new JsonFileAgentStateStore(stateDirectory))) {
            SessionExecutionState previous =
                    restarted.sessionExecution("alice", "restart-session").orElseThrow();
            assertEquals("turn-before-restart", previous.getTurnId());
            assertEquals(TurnStatus.COMPLETED, previous.getStatus());
            AgentRuntimeEvent reply =
                    finalReply(
                            restarted,
                            request(
                                    "turn-after-restart",
                                    "alice",
                                    "restart-session",
                                    "who am I?"))
                            .block(Duration.ofSeconds(5));
            assertEquals("Alice", reply.getText());
        }
    }

    @Test
    void rejectsASecondTurnWhileTheSameOwnerSessionIsRunning() throws Exception {
        BlockingModel model = new BlockingModel();
        try (HarnessAgentRuntime runtime = runtime(model, true)) {
            var first =
                    finalReply(runtime, request("turn-c1", "alice", "one-session", "first"))
                            .subscribeOn(Schedulers.parallel())
                            .toFuture();
            assertTrue(model.entered.await(2, TimeUnit.SECONDS));
            assertThrows(
                    SessionTurnBusyException.class,
                    () ->
                            finalReply(
                                    runtime,
                                    request("turn-c2", "alice", "one-session", "second"))
                                    .block(Duration.ofSeconds(2)));
            model.release.countDown();
            assertEquals("done", first.get(2, TimeUnit.SECONDS).getText());
            SessionExecutionState state =
                    runtime.sessionExecution("alice", "one-session").orElseThrow();
            assertEquals("turn-c1", state.getTurnId());
            assertEquals(TurnStatus.COMPLETED, state.getStatus());
        }
    }

    @Test
    void recordsFailedTurnAndEmitsAStableTerminalEvent() {
        try (HarnessAgentRuntime runtime = runtime(new FailingModel(), true)) {
            List<AgentRuntimeEvent> events =
                    runtime.stream(request("turn-failed", "alice", "failed-session", "fail"))
                            .onErrorResume(ignored -> Flux.empty())
                            .collectList()
                            .block(Duration.ofSeconds(2));

            assertEquals(
                    AgentRuntimeEvent.Type.TURN_FAILED, events.get(events.size() - 1).getType());
            assertEquals(
                    "MODEL_FAILED",
                    ((Map<?, ?>) events.get(events.size() - 1).getDetails()).get("errorCode"));
            assertEquals(
                    "IllegalStateException",
                    ((Map<?, ?>) events.get(events.size() - 1).getDetails()).get("causeType"));
            SessionExecutionState state =
                    runtime.sessionExecution("alice", "failed-session").orElseThrow();
            assertEquals(TurnStatus.FAILED, state.getStatus());
            assertEquals("MODEL_FAILED", state.getFailureCode());
        }
    }

    @Test
    void recordsCancelledTurnWhenTheSubscriberDisconnects() throws Exception {
        NeverModel model = new NeverModel();
        try (HarnessAgentRuntime runtime = runtime(model, true)) {
            Disposable subscription =
                    runtime.stream(request("turn-cancelled", "alice", "cancelled-session", "wait"))
                            .subscribe();
            assertTrue(model.entered.await(2, TimeUnit.SECONDS));
            assertEquals(
                    TurnStatus.RUNNING,
                    runtime.sessionExecution("alice", "cancelled-session")
                            .orElseThrow()
                            .getStatus());

            subscription.dispose();

            assertEquals(
                    TurnStatus.CANCELLED,
                    runtime.sessionExecution("alice", "cancelled-session")
                            .orElseThrow()
                            .getStatus());
        }
    }

    @Test
    void disposingSourceAfterCompletedEventPreservesCompletionAndAllowsNextTurn() {
        try (HarnessAgentRuntime runtime = runtime(new ConversationModel(), true)) {
            // 宿主收到终态后会释放源订阅；资源清理不能把已完成的执行改成取消。
            AgentRuntimeEvent reply =
                    runtime.stream(
                                    request(
                                            "turn-done",
                                            "alice",
                                            "done-session",
                                            "remember:completion-marker"))
                            .takeUntil(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .blockLast(Duration.ofSeconds(2));
            assertEquals(AgentRuntimeEvent.Type.TURN_COMPLETED, reply.getType());
            assertEquals(
                    TurnStatus.COMPLETED,
                    runtime.sessionExecution("alice", "done-session").orElseThrow().getStatus());

            assertEquals(
                    "completion-marker",
                    finalReply(runtime, request("turn-next", "alice", "done-session", "recall"))
                            .block(Duration.ofSeconds(2))
                            .getText());
            assertEquals(
                    TurnStatus.COMPLETED,
                    runtime.sessionExecution("alice", "done-session").orElseThrow().getStatus());
        }
    }

    @Test
    void cancellationImmediatelyAfterTurnStartedDoesNotLeaveSessionBusy() {
        try (HarnessAgentRuntime runtime = runtime(new ConversationModel(), true)) {
            runtime.stream(request("turn-early-cancel", "alice", "early-cancel-session", "hello"))
                    .take(1)
                    .blockLast(Duration.ofSeconds(2));

            assertEquals(
                    TurnStatus.CANCELLED,
                    runtime.sessionExecution("alice", "early-cancel-session")
                            .orElseThrow()
                            .getStatus());
        }
    }

    @Test
    void distinguishesTimeoutFromOtherFailures() {
        try (HarnessAgentRuntime runtime = runtime(new TimeoutModel(), true)) {
            List<AgentRuntimeEvent> events =
                    runtime.stream(request("turn-timeout", "alice", "timeout-session", "wait"))
                            .onErrorResume(ignored -> Flux.empty())
                            .collectList()
                            .block(Duration.ofSeconds(2));

            assertEquals(
                    AgentRuntimeEvent.Type.TURN_TIMED_OUT, events.get(events.size() - 1).getType());
            assertEquals(
                    TurnStatus.TIMED_OUT,
                    runtime.sessionExecution("alice", "timeout-session").orElseThrow().getStatus());
        }
    }

    @Test
    void validatesOpaqueKeysBeforeTouchingAgentScope() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request("turn-1", "../another-owner", "session-1", "hello"));
    }

    @Test
    void requestStringDoesNotExposeOwnerMessageOrBoundContext() {
        AgentTurnRequest request =
                AgentTurnRequest.builder()
                        .turnId("turn-safe-log")
                        .ownerKey("private-owner")
                        .sessionId("session-safe-log")
                        .message("private-message")
                        .context(OwnerMarker.class, new OwnerMarker("private-context"))
                        .build();

        String rendered = request.toString() + request.getContextBindings().get(0);
        assertTrue(!rendered.contains("private-owner"));
        assertTrue(!rendered.contains("private-message"));
        assertTrue(!rendered.contains("private-context"));
    }

    @Test
    void injectsHostContextIntoToolsWithoutPuttingItInModelArguments() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new OwnerContextTool());
        ToolContextModel model = new ToolContextModel();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("context-injection-test")
                        .sysPrompt("Call owner_context once.")
                        .model(model)
                        .toolkit(toolkit)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            AgentTurnRequest request =
                    AgentTurnRequest.builder()
                            .turnId("turn-context")
                            .ownerKey("opaque-owner")
                            .sessionId("session-context")
                            .message("read context")
                            .context(OwnerMarker.class, new OwnerMarker("host-only-value"))
                            .build();

            AgentRuntimeEvent reply = finalReply(runtime, request).block(Duration.ofSeconds(5));

            assertEquals("host-only-value", reply.getText());
            assertTrue(model.sawOwnerTool.get());
            assertTrue(!model.sawHostValue.get());
        }
    }

    @Test
    void pausesAndResumesTheSameTurnWithAgentScopeApprovalState() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new DangerousTool());
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("approval-test")
                        .sysPrompt("Call dangerous_action once.")
                        .model(new DangerousToolModel())
                        .toolkit(toolkit)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .permissionContext(
                                PermissionContextState.builder()
                                        .addAskRule(
                                                "dangerous_action",
                                                new PermissionRule(
                                                        "dangerous_action",
                                                        null,
                                                        PermissionBehavior.ASK,
                                                        "test"))
                                        .build())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            List<AgentRuntimeEvent> paused =
                    runtime.stream(request("turn-approval", "alice", "approval-session", "execute"))
                            .collectList()
                            .block(Duration.ofSeconds(5));
            AgentRuntimeEvent approval =
                    paused.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.APPROVAL_REQUIRED)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    paused.stream()
                                                            .map(AgentRuntimeEvent::getType)
                                                            .toList()));
            assertTrue(
                    paused.stream()
                            .noneMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED));
            assertEquals(
                    TurnStatus.WAITING_APPROVAL,
                    runtime.sessionExecution("alice", "approval-session")
                            .orElseThrow()
                            .getStatus());

            ToolApprovalRequest pending =
                    ((List<?>) approval.getDetails())
                            .stream()
                            .map(ToolApprovalRequest.class::cast)
                            .findFirst()
                            .orElseThrow();
            AgentTurnRequest resume =
                    AgentTurnRequest.builder()
                            .turnId("turn-approval")
                            .ownerKey("alice")
                            .sessionId("approval-session")
                            .message("approved")
                            .approvalDecisions(
                                    List.of(
                                            new ToolApprovalDecision(
                                                    pending.getToolCallId(),
                                                    pending.getToolName(),
                                                    pending.getContent(),
                                                    pending.getInput(),
                                                    true)))
                            .build();
            AgentRuntimeEvent completed = finalReply(runtime, resume).block(Duration.ofSeconds(5));

            assertEquals("executed", completed.getText());
            assertEquals(
                    TurnStatus.COMPLETED,
                    runtime.sessionExecution("alice", "approval-session")
                            .orElseThrow()
                            .getStatus());
        }
    }

    @Test
    void pausesAndResumesTheSameTurnWithAskUserToolResult() {
        Toolkit toolkit = new Toolkit();
        InMemoryAskUserStore store = new InMemoryAskUserStore();
        toolkit.registerAgentTool(new AskUserTool(store));
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("ask-user-test")
                        .sysPrompt("Call ask_user once.")
                        .model(new AskUserModel())
                        .toolkit(toolkit)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            List<AgentRuntimeEvent> paused =
                    runtime.stream(request("turn-ask", "alice", "ask-session", "choose"))
                            .collectList()
                            .block(Duration.ofSeconds(5));
            AgentRuntimeEvent required =
                    paused.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.ASK_USER_REQUIRED)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError(paused.toString()));
            assertEquals(
                    TurnStatus.WAITING_ASK_USER,
                    runtime.sessionExecution("alice", "ask-session").orElseThrow().getStatus());
            AskUserRequest pending = (AskUserRequest) required.getDetails();

            AgentTurnRequest resume =
                    AgentTurnRequest.builder()
                            .turnId("turn-ask")
                            .ownerKey("alice")
                            .sessionId("ask-session")
                            .message("用户已回答问题")
                            .askUserDecisions(
                                    List.of(
                                            new AskUserDecision(
                                                    pending.getAskUserId(),
                                                    pending.getToolCallId(),
                                                    "[{\"questionId\":\"period\",\"selectedOptionIds\":[\"7d\"}]")))
                            .build();
            List<AgentRuntimeEvent> resumed =
                    runtime.stream(resume).collectList().block(Duration.ofSeconds(5));
            assertTrue(
                    resumed.stream()
                            .anyMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.ASK_USER_RESOLVED));
            assertEquals(
                    "answered",
                    resumed.stream()
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .findFirst()
                            .orElseThrow()
                            .getText());
            assertEquals(
                    TurnStatus.COMPLETED,
                    runtime.sessionExecution("alice", "ask-session").orElseThrow().getStatus());
        }
    }

    @Test
    void keepsArtifactReferenceInAgentScopeHistoryForTheNextTurn() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new ArtifactReferenceTool());
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("artifact-history-test")
                        .sysPrompt("Use the artifact tool when producing a file.")
                        .model(new ArtifactHistoryModel())
                        .toolkit(toolkit)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            finalReply(
                    runtime,
                    request(
                            "turn-artifact-1",
                            "alice",
                            "artifact-session",
                            "produce report"))
                    .block(Duration.ofSeconds(5));
            AgentRuntimeEvent next =
                    finalReply(
                            runtime,
                            request(
                                    "turn-artifact-2",
                                    "alice",
                                    "artifact-session",
                                    "continue with the report"))
                            .block(Duration.ofSeconds(5));
            assertEquals("load_artifact art_history_report", next.getText());
        }
    }

    private HarnessAgentRuntime runtime(ChatModelBase model, boolean disableWorkspaceContext) {
        return runtime(model, disableWorkspaceContext, new InMemoryAgentStateStore());
    }

    private HarnessAgentRuntime runtime(
            ChatModelBase model, boolean disableWorkspaceContext, AgentStateStore stateStore) {
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("multi-owner-test")
                        .sysPrompt("Answer deterministically for the test.")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(new LocalFilesystemSpec().isolationScope(IsolationScope.USER))
                        .stateStore(stateStore)
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableSubagents()
                        .disableShellTool();
        if (disableWorkspaceContext) {
            builder.disableWorkspaceContext();
        }
        return new HarnessAgentRuntime(builder.build());
    }

    private void writeSkill(String owner, String description, String body) throws Exception {
        Path directory = workspace.resolve(owner).resolve("skills/greeting");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("SKILL.md"),
                """
                        ---
                        name: greeting
                        description: %s
                        ---
                        %s
                        """
                        .formatted(description, body));
    }

    private static AgentTurnRequest request(
            String turnId, String ownerKey, String sessionId, String message) {
        return AgentTurnRequest.builder()
                .turnId(turnId)
                .ownerKey(ownerKey)
                .sessionId(sessionId)
                .message(message)
                .build();
    }

    private static Mono<AgentRuntimeEvent> finalReply(
            AgentRuntime runtime, AgentTurnRequest request) {
        return runtime.stream(request)
                .filter(event -> event.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED)
                .single()
                .doOnNext(event -> assertTrue(event.getLatencyMs() >= 0));
    }

    private static final class ConversationModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "conversation-isolation-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String latest = latestUserMessage(messages);
            String answer;
            if (latest.startsWith("remember:")) {
                answer = "remembered";
            } else {
                answer =
                        messages.stream()
                                .filter(message -> message.getRole() == MsgRole.USER)
                                .map(Msg::getTextContent)
                                .filter(text -> text.startsWith("remember:"))
                                .map(text -> text.substring("remember:".length()))
                                .findFirst()
                                .orElse("unknown");
            }
            return text(answer);
        }
    }

    private static final class DelegatingModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "execution-tree-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String latest = latestUserMessage(messages);
            if ("child-task".equals(latest)) return text("child-result");
            boolean hasSpawnResult =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .anyMatch(block -> "agent_spawn".equals(block.getName()));
            if (hasSpawnResult) return text("parent-result");
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("spawn-call")
                                                    .name("agent_spawn")
                                                    .input(
                                                            Map.of(
                                                                    "agent_id",
                                                                    "worker",
                                                                    "task",
                                                                    "child-task",
                                                                    "timeout_seconds",
                                                                    30))
                                                    .content(
                                                            "{\"agent_id\":\"worker\",\"task\":\"child-task\","
                                                                    + "\"timeout_seconds\":30}")
                                                    .build()))
                            .build());
        }
    }

    private static final class ImageAwareModel extends ChatModelBase {
        private boolean sawImage;

        @Override
        public String getModelName() {
            return "image-aware-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            Msg latest =
                    messages.stream()
                            .filter(message -> message.getRole() == MsgRole.USER)
                            .reduce((first, second) -> second)
                            .orElseThrow();
            List<ImageBlock> images = latest.getContentBlocks(ImageBlock.class);
            sawImage =
                    images.size() == 1
                            && images.get(0).getSource() instanceof URLSource source
                            && source.getUrl().startsWith("https://bos.example.test/");
            return text(sawImage ? "image-received" : "image-missing");
        }
    }

    private static final class CompactionModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "compaction-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String prompt = latestUserMessage(messages);
            if (prompt.contains("Context Extraction Assistant")) {
                return text("older exchange summary");
            }
            return text("reply:" + prompt);
        }
    }

    private static final class HeadProtectionModel extends ChatModelBase {
        private final String foundingInstruction;
        private int regularCalls;
        private int summaryCalls;

        private HeadProtectionModel(String foundingInstruction) {
            this.foundingInstruction = foundingInstruction;
        }

        @Override
        public String getModelName() {
            return "head-protection-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String prompt = latestUserMessage(messages);
            if (prompt.contains("Context Extraction Assistant")) {
                summaryCalls++;
                return text("older work summary");
            }
            regularCalls++;
            if (regularCalls > 1
                    && messages.stream()
                    .anyMatch(
                            message ->
                                    foundingInstruction.equals(message.getTextContent()))) {
                return text("head-preserved");
            }
            return text("first-reply");
        }
    }

    private static final class OverflowOnceModel extends ChatModelBase {
        private int regularCalls;
        private int summaryCalls;
        private boolean sawProtectedHeadOnRetry;

        @Override
        public String getModelName() {
            return "overflow-once-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String prompt = latestUserMessage(messages);
            if (prompt.contains("Context Extraction Assistant")) {
                summaryCalls++;
                return text("older work summary");
            }
            regularCalls++;
            if (regularCalls == 2) {
                return Flux.error(new IllegalStateException("context_length_exceeded"));
            }
            if (regularCalls == 3) {
                sawProtectedHeadOnRetry =
                        messages.stream()
                                .anyMatch(message -> "first".equals(message.getTextContent()));
            }
            return text("reply:" + prompt);
        }
    }

    private static final class FailingSummaryModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "failing-summary-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String prompt = latestUserMessage(messages);
            if (prompt.contains("Context Extraction Assistant")) {
                return Flux.error(new IllegalStateException("summary unavailable"));
            }
            return text("reply:" + prompt);
        }
    }

    private static final class MultiStepCompactionModel extends ChatModelBase {
        private int summaryCalls;

        @Override
        public String getModelName() {
            return "multi-step-compaction-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String prompt = latestUserMessage(messages);
            if (prompt.contains("Context Extraction Assistant")) {
                summaryCalls++;
                return text("work completed so far");
            }
            long resultCount =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .count();
            if (resultCount < 2) {
                String id = "loop-call-" + resultCount;
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                ToolUseBlock.builder()
                                                        .id(id)
                                                        .name("loop_tool")
                                                        .input(Map.of("step", resultCount + 1))
                                                        .content(
                                                                "{\"step\":"
                                                                        + (resultCount + 1)
                                                                        + "}")
                                                        .build()))
                                .build());
            }
            return text("done");
        }
    }

    private static final class LoopTool extends ToolBase {
        private LoopTool() {
            super(
                    ToolBase.builder()
                            .name("loop_tool")
                            .description("Return a synthetic step result.")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of("step", Map.of("type", "integer")),
                                            "required",
                                            List.of("step"),
                                            "additionalProperties",
                                            false))
                            .readOnly(true)
                            .concurrencySafe(true));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("step:" + param.getInput().get("step")));
        }
    }

    private static final class WorkspaceInstructionModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "workspace-isolation-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String prompt =
                    messages.stream()
                            .map(Msg::getTextContent)
                            .reduce("", (left, right) -> left + "\n" + right);
            if (prompt.contains("ALICE_ONLY_INSTRUCTION")) {
                return text("ALICE_ONLY_INSTRUCTION");
            }
            if (prompt.contains("BOB_ONLY_INSTRUCTION")) {
                return text("BOB_ONLY_INSTRUCTION");
            }
            return text("BASE_INSTRUCTION");
        }
    }

    private static final class ToolContextModel extends ChatModelBase {
        private final AtomicBoolean sawOwnerTool = new AtomicBoolean();
        private final AtomicBoolean sawHostValue = new AtomicBoolean();

        @Override
        public String getModelName() {
            return "tool-context-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            sawOwnerTool.set(
                    tools.stream().anyMatch(tool -> "owner_context".equals(tool.getName())));
            sawHostValue.set(
                    tools.stream()
                            .anyMatch(
                                    tool ->
                                            tool.getParameters()
                                                    .toString()
                                                    .contains("host-only-value")));
            String result =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .flatMap(block -> block.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .findFirst()
                            .orElse(null);
            if (result != null) {
                return text(result);
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("context-call")
                                                    .name("owner_context")
                                                    .input(Map.of())
                                                    .content("{}")
                                                    .build()))
                            .build());
        }
    }

    private static final class SkillLoadingModel extends ChatModelBase {
        private static final Pattern SKILL_ID =
                Pattern.compile("<skill-id>([^<]*greeting[^<]*)</skill-id>");

        @Override
        public String getModelName() {
            return "skill-isolation-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String result =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .flatMap(block -> block.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .findFirst()
                            .orElse(null);
            if (result != null) {
                if (result.contains("ALICE_SKILL_BODY")) {
                    return text("ALICE_SKILL_BODY");
                }
                if (result.contains("BOB_SKILL_BODY")) {
                    return text("BOB_SKILL_BODY");
                }
                return text(result);
            }
            String prompt =
                    messages.stream()
                            .map(Msg::getTextContent)
                            .reduce("", (left, right) -> left + "\n" + right);
            var match = SKILL_ID.matcher(prompt);
            if (!match.find()) {
                return text("NO_GREETING_SKILL");
            }
            String skillId = match.group(1);
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("skill-call")
                                                    .name("load_skill_through_path")
                                                    .input(
                                                            Map.of(
                                                                    "skillId",
                                                                    skillId,
                                                                    "path",
                                                                    "SKILL.md"))
                                                    .content(
                                                            "{\"skillId\":\""
                                                                    + skillId
                                                                    + "\",\"path\":\"SKILL.md\"}")
                                                    .build()))
                            .build());
        }
    }

    private static final class BlockingModel extends ChatModelBase {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String getModelName() {
            return "same-session-rejection-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        entered.countDown();
                        try {
                            if (!release.await(2, TimeUnit.SECONDS)) {
                                return Flux.error(
                                        new IllegalStateException("test release timeout"));
                            }
                            return text("done");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            return Flux.error(error);
                        }
                    });
        }
    }

    private static final class FailingModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "failed-turn-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new IllegalStateException("deliberate failure"));
        }
    }

    private static final class NeverModel extends ChatModelBase {
        private final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public String getModelName() {
            return "cancelled-turn-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            entered.countDown();
            return Flux.never();
        }
    }

    private static final class TimeoutModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "timeout-turn-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new TimeoutException("deliberate timeout"));
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class OwnerMarker {
        private String value;

        public String value() {
            return value;
        }
    }

    private static final class OwnerContextTool implements AgentTool {
        @Override
        public String getName() {
            return "owner_context";
        }

        @Override
        public String getDescription() {
            return "Return the host-bound request marker.";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            OwnerMarker marker = param.getRuntimeContext().get(OwnerMarker.class);
            return Mono.just(ToolResultBlock.text(marker.value()));
        }
    }

    private static final class DangerousTool extends ToolBase {
        private DangerousTool() {
            super(
                    ToolBase.builder()
                            .name("dangerous_action")
                            .description("A test action that requires approval.")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of("value", Map.of("type", "string")),
                                            "required",
                                            List.of("value"),
                                            "additionalProperties",
                                            false))
                            .readOnly(false)
                            .concurrencySafe(true));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("executed"));
        }
    }

    private static final class DangerousToolModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "approval-test-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String result =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .flatMap(block -> block.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .findFirst()
                            .orElse(null);
            if (result != null) {
                return text(result);
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("dangerous-call")
                                                    .name("dangerous_action")
                                                    .input(Map.of("value", "run"))
                                                    .content("{\"value\":\"run\"}")
                                                    .build()))
                            .build());
        }
    }

    private static final class ArtifactReferenceTool extends ToolBase {
        private ArtifactReferenceTool() {
            super(
                    ToolBase.builder()
                            .name("deliver_artifact")
                            .description("Publishes a test artifact.")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(),
                                            "additionalProperties",
                                            false))
                            .readOnly(false)
                            .concurrencySafe(true));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(
                    ToolResultBlock.text(
                            "Delivered report.csv to the configured destination: artifactId=art_history_report"));
        }
    }

    private static final class ArtifactHistoryModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "artifact-history-test-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String reference =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .flatMap(block -> block.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .filter(text -> text.contains("artifactId=art_history_report"))
                            .findFirst()
                            .orElse(null);
            if (reference != null) return text("load_artifact art_history_report");
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("artifact-call")
                                                    .name("deliver_artifact")
                                                    .input(Map.of())
                                                    .content("{}")
                                                    .build()))
                            .build());
        }
    }

    private static final class AskUserModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "ask-user-test-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean answered =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .anyMatch(block -> "ask_user".equals(block.getName()));
            if (answered) return text("answered");
            Map<String, Object> question =
                    Map.of(
                            "questionId",
                            "period",
                            "type",
                            "single",
                            "title",
                            "统计周期",
                            "required",
                            true,
                            "options",
                            List.of(
                                    Map.of("optionId", "7d", "label", "最近七天"),
                                    Map.of("optionId", "30d", "label", "最近三十天")));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("ask-call")
                                                    .name("ask_user")
                                                    .input(Map.of("questions", List.of(question)))
                                                    .content(
                                                            """
                                                                    {"questions":[{"questionId":"period","type":"single","title":"统计周期","required":true,"options":[{"optionId":"7d","label":"最近七天"},{"optionId":"30d","label":"最近三十天"}]}]}
                                                                    """)
                                                    .build()))
                            .build());
        }
    }

    private static final class InMemoryAskUserStore implements AskUserStore {
        private final Map<String, AskUserRequest> values = new ConcurrentHashMap<>();

        @Override
        public AskUserRequest createOrFind(AskUserRequest request) {
            return values.computeIfAbsent(request.getAskUserId(), ignored -> request);
        }

        @Override
        public Optional<AskUserRequest> find(String ownerKey, String askUserId) {
            AskUserRequest request = values.get(askUserId);
            return request != null && request.getOwnerKey().equals(ownerKey)
                    ? Optional.of(request)
                    : Optional.empty();
        }

        @Override
        public List<AskUserRequest> findPending(String ownerKey, String sessionId, String turnId) {
            return values.values().stream()
                    .filter(
                            request ->
                                    request.getOwnerKey().equals(ownerKey)
                                            && request.getSessionId().equals(sessionId)
                                            && request.getTurnId().equals(turnId)
                                            && request.getStatus() == AskUserStatus.PENDING)
                    .toList();
        }

        @Override
        public AskUserRequest resolve(
                String ownerKey,
                String askUserId,
                AskUserStatus status,
                String answersJson,
                Instant resolvedAt,
                long expectedVersion) {
            return find(ownerKey, askUserId).orElseThrow();
        }
    }

    private static String latestUserMessage(List<Msg> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (message.getRole() == MsgRole.USER) {
                return message.getTextContent();
            }
        }
        return "";
    }

    private static Flux<ChatResponse> text(String value) {
        return Flux.just(
                ChatResponse.builder()
                        .content(List.<ContentBlock>of(TextBlock.builder().text(value).build()))
                        .build());
    }
}
