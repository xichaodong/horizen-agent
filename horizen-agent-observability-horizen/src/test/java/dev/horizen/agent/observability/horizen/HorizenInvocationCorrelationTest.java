package dev.horizen.agent.observability.horizen;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.observability.TraceDataSanitizer;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tool.AgentSpawnTool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class HorizenInvocationCorrelationTest {
    @TempDir Path workspace;

    @Test
    void realConcurrentChildCallsLinkToTheirExactDelegationSpans() {
        var batches = new CopyOnWriteArrayList<HorizenTraceBatch>();
        var permissions =
                PermissionContextState.builder()
                        .addAllowRule(
                                "agent_spawn",
                                new PermissionRule(
                                        "agent_spawn",
                                        null,
                                        PermissionBehavior.ALLOW,
                                        "Synthetic delegation"))
                        .build();
        var traceConfig = HorizenTraceConfig.local(URI.create("http://localhost"), 7);
        traceConfig.setCaptureContent(true);
        var model = new DelegatingModel();
        var agent =
                HarnessAgent.builder()
                        .name("parent")
                        .sysPrompt("Delegate synthetic tasks.")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .permissionContext(permissions)
                        .subagent(
                                SubagentDeclaration.builder()
                                        .name("worker")
                                        .description("Synthetic worker")
                                        .inlineAgentsBody("Return the synthetic child result.")
                                        .steps(2)
                                        .build())
                        .middleware(
                                new HorizenTracingMiddleware(
                                        traceConfig,
                                        batch -> {
                                            batches.add(batch);
                                            return true;
                                        }))
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableShellTool()
                        .disableTranscript()
                        .build();
        try (var runtime =
                new HarnessAgentRuntime(
                        agent, ctx -> ctx.put(AgentSpawnTool.CTX_FORCE_SYNC, true), null)) {
            runtime.stream(
                            AgentTurnRequest.builder()
                                    .ownerKey("owner")
                                    .sessionId("session")
                                    .turnId("turn")
                                    .message("delegate")
                                    .context(
                                            HorizenTraceContext.class,
                                            new HorizenTraceContext(
                                                    "turn",
                                                    "parent-trace",
                                                    "session",
                                                    "actor",
                                                    "parent",
                                                    Map.of()))
                                    .build())
                    .collectList()
                    .block(Duration.ofSeconds(10));
        }
        Map<String, HorizenTraceBatch> latest = new HashMap<>();
        batches.forEach(batch -> latest.put(batch.getTrace().getTraceId(), batch));
        assertEquals(
                3,
                latest.size(),
                latest.get("parent-trace").getSpans().stream()
                        .filter(span -> "TOOL".equals(span.getSpanType()))
                        .map(span -> span.getOutput())
                        .toList()
                        .toString());
        var parent = latest.get("parent-trace");
        assertEquals("COMPLETED", parent.getTrace().getStatus());
        Set<String> delegationSpans = new HashSet<>();
        parent.getSpans().stream()
                .filter(span -> "agent_spawn".equals(span.getName()))
                .forEach(span -> delegationSpans.add(span.getSpanId()));
        Set<String> linked = new HashSet<>();
        for (var batch : latest.values()) {
            assertEquals("turn", batch.getTrace().getTurnId());
            assertEquals("session", batch.getTrace().getSessionId());
            assertNotNull(batch.getTrace().getInvocationId());
            if (batch == parent) continue;
            assertEquals("parent-trace", batch.getTrace().getParentTraceId());
            assertEquals("subagent", batch.getTrace().getInvocationKind());
            assertEquals("COMPLETED", batch.getTrace().getStatus());
            linked.add(batch.getTrace().getParentSpanId());
        }
        assertEquals(2, linked.size());
        assertEquals(2, model.maxConcurrentChildren.get(), "Delegations should actually overlap");
        assertEquals(delegationSpans, linked);
        assertEquals(
                3,
                latest.values().stream()
                        .map(batch -> batch.getTrace().getInvocationId())
                        .distinct()
                        .count());
    }

    @Test
    void resumedInvocationsKeepTurnAndCreateNewInvocationIds() {
        var config = HorizenTraceConfig.local(URI.create("http://localhost"), 7);
        var batches = new ArrayList<HorizenTraceBatch>();
        var agent = ReActAgent.builder().name("test").model(new DelegatingModel()).build();
        for (String kind : List.of("turn", "approval_resume", "ask_user_resume")) {
            var ctx =
                    RuntimeContext.builder()
                            .sessionId("session")
                            .put(
                                    HorizenTraceContext.class,
                                    HorizenTraceContext.of("turn", "session"))
                            .put("agent.invocationKind", kind)
                            .build();
            var run =
                    HorizenTraceRun.start(
                            config,
                            batch -> {
                                batches.add(batch);
                                return true;
                            },
                            new TraceDataSanitizer(new ObjectMapper()),
                            agent,
                            ctx,
                            List.of());
            run.complete();
        }
        var finals =
                batches.stream()
                        .filter(batch -> "COMPLETED".equals(batch.getTrace().getStatus()))
                        .toList();
        assertEquals(
                1, finals.stream().map(batch -> batch.getTrace().getTurnId()).distinct().count());
        assertEquals(
                3, finals.stream().map(batch -> batch.getTrace().getTraceId()).distinct().count());
        assertEquals(
                3,
                finals.stream()
                        .map(batch -> batch.getTrace().getInvocationId())
                        .distinct()
                        .count());
        assertEquals(
                List.of("turn", "approval_resume", "ask_user_resume"),
                finals.stream().map(batch -> batch.getTrace().getInvocationKind()).toList());
    }

    private static final class DelegatingModel extends ChatModelBase {
        private final AtomicInteger activeChildren = new AtomicInteger();
        private final AtomicInteger maxConcurrentChildren = new AtomicInteger();

        @Override
        public String getModelName() {
            return "synthetic-correlation-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean child =
                    messages.stream()
                            .filter(message -> message.getRole() == MsgRole.USER)
                            .reduce((a, b) -> b)
                            .orElseThrow()
                            .getTextContent()
                            .startsWith("synthetic-");
            boolean result =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .anyMatch(block -> "agent_spawn".equals(block.getName()));

            if (child)
                return Flux.defer(
                        () -> {
                            maxConcurrentChildren.accumulateAndGet(
                                    activeChildren.incrementAndGet(), Math::max);
                            return Flux.just(
                                            ChatResponse.builder()
                                                    .content(
                                                            List.of(
                                                                    TextBlock.builder()
                                                                            .text("child complete")
                                                                            .build()))
                                                    .build())
                                    .delayElements(Duration.ofMillis(50))
                                    .doFinally(signal -> activeChildren.decrementAndGet());
                        });
            if (result)
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("parent complete")
                                                        .build()))
                                .build());
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("spawn-a")
                                                    .name("agent_spawn")
                                                    .input(
                                                            Map.of(
                                                                    "agent_id",
                                                                    "worker",
                                                                    "task",
                                                                    "synthetic-a"))
                                                    .content(
                                                            "{\"agent_id\":\"worker\",\"task\":\"synthetic-a\"}")
                                                    .build(),
                                            ToolUseBlock.builder()
                                                    .id("spawn-b")
                                                    .name("agent_spawn")
                                                    .input(
                                                            Map.of(
                                                                    "agent_id",
                                                                    "worker",
                                                                    "task",
                                                                    "synthetic-b"))
                                                    .content(
                                                            "{\"agent_id\":\"worker\",\"task\":\"synthetic-b\"}")
                                                    .build()))
                            .build());
        }
    }
}
