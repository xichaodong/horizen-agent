package dev.horizen.agent.observability.horizen;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import dev.horizen.agent.observability.TraceDataSanitizer;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

class HorizenTracingMiddlewareTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void capturesAgentModelAndToolSpansInHorizenContract() throws Exception {
        List<HorizenTraceBatch> batches = new ArrayList<>();
        var config = config(true);
        ReActAgent agent =
                agent(
                        config,
                        batch -> {
                            synchronized (batches) {
                                batches.add(batch);
                            }
                            return true;
                        });
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("session-fallback")
                        .put(
                                HorizenTraceContext.class,
                                new HorizenTraceContext(
                                        "run-42",
                                        "trace-42",
                                        "session-42",
                                        "user-42",
                                        "test-run",
                                        Map.of("environment", "test")))
                        .build();

        Msg result =
                agent.call(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("Hello, Horizen!")
                                                .build()),
                                context)
                        .block(Duration.ofSeconds(5));

        assertNotNull(result);
        assertEquals("Tool returned: Hello, Horizen!", result.getTextContent());
        HorizenTraceBatch completed;
        synchronized (batches) {
            completed = batches.get(batches.size() - 1);
        }
        assertEquals(1, completed.getContractVersion());
        assertEquals(7L, completed.getProjectId());
        assertEquals("trace-42", completed.getTrace().getTraceId());
        assertEquals("session-42", completed.getTrace().getSessionId());
        assertEquals("user-42", completed.getTrace().getUserId());
        assertEquals("COMPLETED", completed.getTrace().getStatus());
        assertEquals("Hello, Horizen!", completed.getTrace().getInput());
        assertEquals("Tool returned: Hello, Horizen!", completed.getTrace().getOutput());
        assertEquals(
                1,
                completed.getSpans().stream().filter(s -> s.getSpanType().equals("AGENT")).count());
        assertEquals(
                2,
                completed.getSpans().stream().filter(s -> s.getSpanType().equals("LLM")).count());
        assertEquals(
                1,
                completed.getSpans().stream().filter(s -> s.getSpanType().equals("TOOL")).count());
        var tool =
                completed.getSpans().stream()
                        .filter(s -> s.getSpanType().equals("TOOL"))
                        .findFirst()
                        .orElseThrow();
        var firstLlm =
                completed.getSpans().stream()
                        .filter(s -> s.getSpanType().equals("LLM"))
                        .findFirst()
                        .orElseThrow();
        var root =
                completed.getSpans().stream()
                        .filter(s -> s.getSpanType().equals("AGENT"))
                        .findFirst()
                        .orElseThrow();
        assertNull(root.getInput());
        assertNull(root.getOutput());
        assertInstanceOf(Map.class, firstLlm.getInput());
        assertEquals("OK", tool.getStatus());
        assertEquals(firstLlm.getSpanId(), tool.getParentSpanId());
        assertEquals("demo-echo-1", ((Map<?, ?>) tool.getMetadata()).get("toolCallId"));
        assertTrue(mapper.writeValueAsString(tool.getInput()).contains("Hello, Horizen!"));
        assertTrue(mapper.writeValueAsString(tool.getOutput()).contains("Hello, Horizen!"));
        var llmUsage =
                completed.getSpans().stream()
                        .filter(s -> s.getSpanType().equals("LLM"))
                        .map(HorizenTraceBatch.Span::getUsage)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElseThrow();
        assertTrue(mapper.writeValueAsString(llmUsage).contains("inputTokens"));
        assertTrue(
                completed.getEvents().stream().anyMatch(e -> e.getEventType().equals("TOOL_CALL")));
        assertTrue(
                completed.getEvents().stream()
                        .anyMatch(e -> e.getEventType().equals("TOOL_RESULT")));
        assertTrue(
                completed.getEvents().stream().anyMatch(e -> e.getName().equals("agent.message")));
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(
                        Path.of("target", "horizen-trace-contract-v1.json").toFile(), completed);
    }

    @Test
    void contentCaptureIsOffByDefaultAndSinkFailuresAreFailOpen() throws Exception {
        List<HorizenTraceBatch> batches = new ArrayList<>();
        var config = config(false);
        AtomicInteger calls = new AtomicInteger();
        HorizenTraceBatchSink flaky =
                batch -> {
                    if (calls.incrementAndGet() == 1)
                        throw new IllegalStateException("collector unavailable");
                    synchronized (batches) {
                        batches.add(batch);
                    }
                    return true;
                };
        Msg result =
                agent(config, flaky)
                        .call(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("secret-free synthetic input")
                                                .build()),
                                RuntimeContext.builder().sessionId("session-1").build())
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        HorizenTraceBatch completed;
        synchronized (batches) {
            completed = batches.get(batches.size() - 1);
        }
        String json = mapper.writeValueAsString(completed);
        assertNull(completed.getTrace().getInput());
        assertNull(completed.getTrace().getOutput());
        assertFalse(json.contains("secret-free synthetic input"));
        assertFalse(json.contains("Hello, Horizen!"));
        assertTrue(json.contains("contentCaptured"));
    }

    @Test
    void unavailableHorizenEndpointDoesNotFailAgentExecution() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/sdk/traces/batch",
                exchange -> {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var config =
                    new HorizenTraceConfig(
                            base,
                            7,
                            null,
                            "horizen-agent",
                            "horizen-agent-java",
                            "test",
                            "trace-test",
                            "test-executor",
                            "test",
                            null,
                            true,
                            Duration.ofSeconds(1),
                            Duration.ZERO,
                            16,
                            Duration.ofSeconds(1));
            try (var exporter = new HorizenHttpBatchExporter(config)) {
                Msg result =
                        agent(config, exporter)
                                .call(
                                        List.of(
                                                Msg.builder()
                                                        .role(MsgRole.USER)
                                                        .textContent("Hello, Horizen!")
                                                        .build()),
                                        RuntimeContext.builder().sessionId("session-1").build())
                                .block(Duration.ofSeconds(5));
                assertNotNull(result);
                assertEquals("Tool returned: Hello, Horizen!", result.getTextContent());
                assertTrue(exporter.flush(Duration.ofSeconds(3)));
                assertTrue(exporter.failedCount() >= 1);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recordsApprovalRequestAndDecisionEvents() {
        List<HorizenTraceBatch> batches = new ArrayList<>();
        HorizenTraceBatchSink sink =
                batch -> {
                    batches.add(batch);
                    return true;
                };
        var config = config(true);
        ReActAgent agent = agent(config, ignored -> true);
        HorizenTraceRun run =
                HorizenTraceRun.start(
                        config,
                        sink,
                        new TraceDataSanitizer(mapper),
                        agent,
                        RuntimeContext.builder().sessionId("approval-session").build(),
                        List.of(Msg.builder().role(MsgRole.USER).textContent("run").build()));
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("tool-approval-1")
                        .name("dangerous")
                        .input(Map.of("value", "raw"))
                        .content("{\"value\":\"raw\"}")
                        .build();

        run.observeAgentEvent(new RequireUserConfirmEvent("reply-1", List.of(toolCall)));
        run.observeAgentEvent(
                new UserConfirmResultEvent("reply-1", List.of(new ConfirmResult(true, toolCall))));
        run.complete();

        HorizenTraceBatch completed = batches.get(batches.size() - 1);
        assertTrue(
                completed.getEvents().stream()
                        .anyMatch(event -> event.getEventType().equals("APPROVAL_REQUEST")));
        assertTrue(
                completed.getEvents().stream()
                        .anyMatch(event -> event.getEventType().equals("APPROVAL_RESULT")));
        assertTrue(
                mapper.valueToTree(completed.getEvents()).toString().contains("tool-approval-1"));
    }

    @Test
    void recordsDirectCompactionModelCallAsDedicatedLlmSpan() {
        List<HorizenTraceBatch> batches = new ArrayList<>();
        HorizenTraceBatchSink sink =
                batch -> {
                    batches.add(batch);
                    return true;
                };
        HorizenTraceConfig config = config(true);
        ReActAgent agent = agent(config, ignored -> true);
        HorizenTraceRun run =
                HorizenTraceRun.start(
                        config,
                        sink,
                        new TraceDataSanitizer(mapper),
                        agent,
                        RuntimeContext.builder().sessionId("compaction-session").build(),
                        List.of(Msg.builder().role(MsgRole.USER).textContent("start").build()));
        ChatModelBase delegate =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "summary-model";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.<ContentBlock>of(
                                                        TextBlock.builder()
                                                                .text("compact summary")
                                                                .build()))
                                        .usage(new ChatUsage(120, 18, 40, 0.12))
                                        .build());
                    }
                };
        HorizenObservedCompactionModel observed = new HorizenObservedCompactionModel(delegate);

        observed.stream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("summarize synthetic history")
                                        .build()),
                        List.of(),
                        null)
                .contextWrite(
                        context -> context.put(HorizenTracingMiddleware.REACTOR_STATE_KEY, run))
                .collectList()
                .block(Duration.ofSeconds(2));
        run.complete();

        HorizenTraceBatch completed = batches.get(batches.size() - 1);
        HorizenTraceBatch.Span span =
                completed.getSpans().stream()
                        .filter(value -> value.getName().startsWith("llm.compaction."))
                        .findFirst()
                        .orElseThrow();
        assertEquals("LLM", span.getSpanType());
        assertEquals("summary-model", span.getModel());
        assertTrue(mapper.valueToTree(span.getUsage()).toString().contains("cacheReadInputTokens"));
        assertEquals("compaction", ((Map<?, ?>) span.getMetadata()).get("operation"));
    }

    private static ReActAgent agent(HorizenTraceConfig config, HorizenTraceBatchSink sink) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new EchoTool());
        return ReActAgent.builder()
                .name("trace-test")
                .sysPrompt("Use echo.")
                .model(new ScriptedModel())
                .toolkit(toolkit)
                .middleware(new HorizenTracingMiddleware(config, sink))
                .build();
    }

    private static HorizenTraceConfig config(boolean captureContent) {
        return new HorizenTraceConfig(
                URI.create("http://localhost"),
                7,
                null,
                "horizen-agent",
                "horizen-agent-java",
                "test",
                "trace-test",
                "test-executor",
                "test",
                null,
                captureContent,
                Duration.ofSeconds(1),
                Duration.ZERO,
                16,
                Duration.ofSeconds(1));
    }

    private static final class ScriptedModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (calls.getAndIncrement() == 0) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                ToolUseBlock.builder()
                                                        .id("demo-echo-1")
                                                        .name("echo")
                                                        .input(Map.of("text", "Hello, Horizen!"))
                                                        .content("{\"text\":\"Hello, Horizen!\"}")
                                                        .build()))
                                .usage(new ChatUsage(10, 2, 0.01))
                                .build());
            }
            String text =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .flatMap(value -> value.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .findFirst()
                            .orElseThrow();
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            TextBlock.builder()
                                                    .text("Tool returned: " + text)
                                                    .build()))
                            .usage(new ChatUsage(12, 5, 0.02))
                            .build());
        }
    }

    private static final class EchoTool implements AgentTool {
        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "Return text unchanged.";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("text", Map.of("type", "string")),
                    "required",
                    List.of("text"));
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text((String) param.getInput().get("text")));
        }
    }
}
