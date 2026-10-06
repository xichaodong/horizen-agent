package dev.horizen.agent.adapter.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.adapter.agentscope.askuser.AskUserTool;
import dev.horizen.agent.adapter.agentscope.workspace.release.PublicationStateMiddleware;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.AskUserDecision;
import dev.horizen.agent.runtime.api.ToolApprovalDecision;
import dev.horizen.agent.runtime.api.ToolApprovalRequest;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.*;
import io.agentscope.harness.agent.tool.AgentSpawnTool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

class SubagentLifecycleAcceptanceTest {
    @TempDir
    Path workspace;

    @Test
    void childAllowlistFiltersSchemasAndBlocksForgedCalls() {
        var effects = new AtomicInteger();
        var model = new DelegationModel("forbidden");
        try (var runtime = runtime(model, effects, Set.of("safe_tool"), false, 5)) {
            var events = execute(runtime).collectList().block(Duration.ofSeconds(5));
            assertEquals(0, effects.get());
            assertFalse(model.childSchemas.contains("dangerous_action"));
            assertTrue(
                    model.childSchemas.contains("safe_tool"), types(events) + model.spawnResults);
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.SUBAGENT_STARTED));
        }
    }

    @Test
    void childApprovalPausesParentWithoutFinishingIt() {
        verifyApproval("approval");
    }

    @Test
    void parentAskPolicyCannotBeRelaxedByTheChild() {
        verifyApproval("parent_rule");
    }

    private void verifyApproval(String mode) {
        var effects = new AtomicInteger();
        var model = new DelegationModel(mode);
        try (var runtime = runtime(model, effects, Set.of("dangerous_action"), true, 5)) {
            var events = execute(runtime).collectList().block(Duration.ofSeconds(5));
            assertEquals(0, effects.get());
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED),
                    types(events) + model.spawnResults);
            var approval =
                    events.stream()
                            .filter(e -> e.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED)
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "dangerous_action",
                    ((ToolApprovalRequest) ((List<?>) approval.getDetails()).get(0)).getToolName());
            var pending = (ToolApprovalRequest) ((List<?>) approval.getDetails()).get(0);
            assertEquals("parent-action-call", pending.getToolCallId());
            assertEquals(Map.of("value", "frozen"), pending.getInput());
            assertFalse(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED),
                    types(events));
            assertEquals(
                    TurnStatus.WAITING_APPROVAL,
                    runtime.sessionExecution("owner", "parent-session").orElseThrow().getStatus());
            var resumed =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .ownerKey("owner")
                                            .sessionId("parent-session")
                                            .turnId("parent-turn")
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
            assertEquals(1, effects.get());
            assertEquals(
                    1,
                    resumed.stream()
                            .filter(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .count());
            assertTrue(
                    resumed.stream()
                            .anyMatch(
                                    e ->
                                            e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED
                                                    && e.getText().contains("executed:frozen")));
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e.getType() == AgentRuntimeEvent.Type.TOOL_COMPLETED
                                                    && "denied".equals(e.getStatus())
                                                    && e.getSource() != null));
        }
    }

    @Test
    void childClarificationUsesOnlyTheParentsQuestionAndResumesTheParent() {
        var model = new DelegationModel("clarification");
        try (var runtime = runtime(model, new AtomicInteger(), Set.of("ask_user"), false, 5)) {
            var events = execute(runtime).collectList().block(Duration.ofSeconds(5));
            var required =
                    events.stream()
                            .filter(e -> e.getType() == AgentRuntimeEvent.Type.ASK_USER_REQUIRED)
                            .findFirst()
                            .orElseThrow();
            AskUserRequest pending = (AskUserRequest) required.getDetails();
            assertEquals("parent-session", pending.getSessionId());
            assertEquals("parent-action-call", pending.getToolCallId());
            assertNull(required.getSource());
            assertEquals(
                    1, model.questions.values.size(), "Child must not create a separate question");
            assertFalse(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED));
            String answer = "[{\"questionId\":\"scope\",\"selectedOptionIds\":[\"week\"]}]";
            var resumed =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .ownerKey("owner")
                                            .sessionId("parent-session")
                                            .turnId("parent-turn")
                                            .message("answered")
                                            .askUserDecisions(
                                                    List.of(
                                                            new AskUserDecision(
                                                                    pending.getAskUserId(),
                                                                    pending.getToolCallId(),
                                                                    answer)))
                                            .build())
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertEquals(
                    1,
                    resumed.stream()
                            .filter(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .count());
            assertEquals(
                    TurnStatus.COMPLETED,
                    runtime.sessionExecution("owner", "parent-session").orElseThrow().getStatus());
        }
    }

    @Test
    void cancellingParentCancelsTheLiveChildSubscription() throws Exception {
        var model = new DelegationModel("wait");
        try (var runtime = runtime(model, new AtomicInteger(), Set.of("safe_tool"), false, 10)) {
            var subscription =
                    execute(runtime).subscribeOn(Schedulers.boundedElastic()).subscribe();
            assertTrue(model.entered.await(3, TimeUnit.SECONDS), model.spawnResults);
            subscription.dispose();
            assertTrue(model.stopped.await(3, TimeUnit.SECONDS), "Child survived cancellation");
            assertEquals(
                    TurnStatus.CANCELLED,
                    runtime.sessionExecution("owner", "parent-session").orElseThrow().getStatus());
        }
    }

    @Test
    void forceSyncTimeoutCancelsChildWithoutCreatingBackgroundWork() throws Exception {
        var model = new DelegationModel("wait");
        try (var runtime = runtime(model, new AtomicInteger(), Set.of("safe_tool"), false, 1)) {
            var events = execute(runtime).collectList().block(Duration.ofSeconds(5));
            assertTrue(
                    model.entered.await(1, TimeUnit.SECONDS), types(events) + model.spawnResults);
            assertTrue(
                    model.stopped.await(3, TimeUnit.SECONDS), "Child survived force_sync timeout");
            assertTrue(runtime.listSubtasks("owner", "parent-session").isEmpty());
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED));
        }
    }

    @Test
    void hostTimeoutStopsTheChildAndKeepsTheTimeoutState() throws Exception {
        var model = new DelegationModel("wait");
        try (var runtime = runtime(model, new AtomicInteger(), Set.of("safe_tool"), false, 10)) {
            var subscription =
                    execute(runtime).subscribeOn(Schedulers.boundedElastic()).subscribe();
            assertTrue(model.entered.await(3, TimeUnit.SECONDS));
            assertTrue(runtime.timeoutCurrentTurn("owner", "parent-session", "parent-turn"));
            subscription.dispose();
            assertTrue(model.stopped.await(3, TimeUnit.SECONDS));
            assertEquals(
                    TurnStatus.TIMED_OUT,
                    runtime.sessionExecution("owner", "parent-session").orElseThrow().getStatus());
        }
    }

    private HarnessAgentRuntime runtime(
            DelegationModel model,
            AtomicInteger effects,
            Set<String> allowed,
            boolean approval,
            int timeout) {
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new CounterTool("safe_tool", true, effects, false));
        toolkit.registerAgentTool(
                new CounterTool(
                        "dangerous_action",
                        false,
                        effects,
                        approval && !"parent_rule".equals(model.mode)));
        toolkit.registerAgentTool(new AskUserTool(model.questions));
        var permission = PermissionContextState.builder();
        permission.addAllowRule(
                "agent_spawn",
                new PermissionRule(
                        "agent_spawn", null, PermissionBehavior.ALLOW, "test delegation"));
        permission.addAllowRule(
                "ask_user",
                new PermissionRule(
                        "ask_user", null, PermissionBehavior.ALLOW, "test clarification"));
        if (approval)
            permission.addAskRule(
                    "dangerous_action",
                    new PermissionRule("dangerous_action", null, PermissionBehavior.ASK, "test"));
        var agent =
                HarnessAgent.builder()
                        .name("parent")
                        .sysPrompt("Delegate to worker.")
                        .model(model)
                        .toolkit(toolkit)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .permissionContext(permission.build())
                        .subagent(
                                SubagentDeclaration.builder()
                                        .name("worker")
                                        .description("Synthetic lifecycle worker")
                                        .workspaceMode(WorkspaceMode.SHARED)
                                        .inlineAgentsBody("Only handle the delegated task.")
                                        .tools(new ArrayList<>(allowed))
                                        .steps(3)
                                        .build())
                        .middleware(
                                new PublicationStateMiddleware(
                                        null, toolkit.getActiveGroups(), Map.of("worker", allowed)))
                        .middleware(new SubagentResultForwardingMiddleware())
                        .middleware(new SubagentInteractionMiddleware())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableShellTool()
                        .disableTranscript()
                        .build();
        return new HarnessAgentRuntime(
                agent,
                context -> {
                    context.put(AgentSpawnTool.CTX_FORCE_SYNC, true);
                    context.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, timeout);
                },
                null);
    }

    private static Flux<AgentRuntimeEvent> execute(HarnessAgentRuntime runtime) {
        return runtime.stream(
                AgentTurnRequest.builder()
                        .ownerKey("owner")
                        .sessionId("parent-session")
                        .turnId("parent-turn")
                        .message("delegate")
                        .build());
    }

    private static String types(List<AgentRuntimeEvent> events) {
        return events.stream().map(AgentRuntimeEvent::getType).toList().toString();
    }

    private static final class CounterTool extends ToolBase {
        private final AtomicInteger effects;
        private final boolean requiresApproval;

        private CounterTool(
                String name, boolean readOnly, AtomicInteger effects, boolean requiresApproval) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description("Synthetic tool")
                            .readOnly(readOnly)
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of("value", Map.of("type", "string")))));
            this.effects = effects;
            this.requiresApproval = requiresApproval;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState context) {
            return requiresApproval
                    ? Mono.just(PermissionDecision.ask("Synthetic approval required"))
                    : super.checkPermissions(input, context);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            effects.incrementAndGet();
            return Mono.just(ToolResultBlock.text("executed:" + param.getInput().get("value")));
        }
    }

    private static final class DelegationModel extends ChatModelBase {
        private final String mode;
        private final Set<String> childSchemas = new HashSet<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private volatile String spawnResults = "";
        private final Questions questions = new Questions();

        private DelegationModel(String mode) {
            this.mode = mode;
        }

        @Override
        public String getModelName() {
            return "subagent-lifecycle-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String last =
                    messages.stream()
                            .filter(m -> m.getRole() == MsgRole.USER)
                            .reduce((a, b) -> b)
                            .orElseThrow()
                            .getTextContent();
            if (last.startsWith("child-")) {
                tools.forEach(tool -> childSchemas.add(tool.getName()));
                if ("wait".equals(mode))
                    return Flux.<ChatResponse>never()
                            .doOnSubscribe(s -> entered.countDown())
                            .doFinally(signal -> stopped.countDown());
                boolean hasResult =
                        messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
                if (hasResult) return text("child-result");
                return "clarification".equals(mode)
                        ? call(
                        "child-call",
                        "ask_user",
                        Map.of(
                                "questions",
                                List.of(
                                        Map.of(
                                                "questionId",
                                                "scope",
                                                "title",
                                                "请选择范围",
                                                "type",
                                                "single",
                                                "required",
                                                true,
                                                "options",
                                                List.of(
                                                        Map.of(
                                                                "optionId",
                                                                "week",
                                                                "label",
                                                                "一周"),
                                                        Map.of(
                                                                "optionId",
                                                                "month",
                                                                "label",
                                                                "一月"))))))
                        : call("child-call", "dangerous_action", Map.of("value", "frozen"));
            }
            boolean hasSpawn =
                    messages.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .anyMatch(result -> "agent_spawn".equals(result.getName()));
            if (hasSpawn)
                spawnResults =
                        messages.stream()
                                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                                .flatMap(result -> result.getOutput().stream())
                                .filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast)
                                .map(TextBlock::getText)
                                .collect(Collectors.joining("\n"));
            boolean rootActionFinished =
                    messages.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .anyMatch(
                                    result ->
                                            "dangerous_action".equals(result.getName())
                                                    || "ask_user".equals(result.getName()));
            if (hasSpawn
                    && Set.of("approval", "parent_rule", "clarification").contains(mode)
                    && !rootActionFinished) {
                Msg handoff =
                        messages.stream()
                                .filter(
                                        m ->
                                                SubagentInteractionMiddleware.REQUEST_MESSAGE
                                                        .equals(m.getName()))
                                .findFirst()
                                .orElseThrow();
                Map<?, ?> body =
                        JsonUtils.getJsonCodec().fromJson(handoff.getTextContent(), Map.class);
                Map<?, ?> proposed = (Map<?, ?>) ((List<?>) body.get("requests")).get(0);
                @SuppressWarnings("unchecked")
                Map<String, Object> frozen = (Map<String, Object>) proposed.get("input");
                return call("parent-action-call", proposed.get("toolName").toString(), frozen);
            }
            return hasSpawn
                    ? text("parent-result:" + spawnResults)
                    : call(
                    "spawn-call",
                    "agent_spawn",
                    Map.of(
                            "agent_id",
                            "worker",
                            "task",
                            "child-" + mode,
                            "timeout_seconds",
                            30));
        }
    }

    private static final class Questions implements AskUserStore {
        private final Map<String, AskUserRequest> values = new ConcurrentHashMap<>();

        @Override
        public AskUserRequest createOrFind(AskUserRequest request) {
            return values.computeIfAbsent(request.getAskUserId(), id -> request);
        }

        @Override
        public Optional<AskUserRequest> find(String owner, String id) {
            return Optional.ofNullable(values.get(id)).filter(r -> owner.equals(r.getOwnerKey()));
        }

        @Override
        public List<AskUserRequest> findPending(String owner, String session, String turn) {
            return values.values().stream()
                    .filter(
                            r ->
                                    owner.equals(r.getOwnerKey())
                                            && session.equals(r.getSessionId())
                                            && turn.equals(r.getTurnId()))
                    .toList();
        }

        @Override
        public AskUserRequest resolve(
                String owner,
                String id,
                AskUserStatus status,
                String answers,
                Instant at,
                long version) {
            return find(owner, id).orElseThrow();
        }
    }

    private static Flux<ChatResponse> call(String id, String name, Map<String, Object> input) {
        return Flux.just(
                ChatResponse.builder()
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id(id)
                                                .name(name)
                                                .input(input)
                                                .content(JsonUtils.getJsonCodec().toJson(input))
                                                .build()))
                        .build());
    }

    private static Flux<ChatResponse> text(String value) {
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(value).build()))
                        .build());
    }
}
