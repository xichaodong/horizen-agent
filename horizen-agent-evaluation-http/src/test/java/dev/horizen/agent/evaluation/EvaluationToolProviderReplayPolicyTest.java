package dev.horizen.agent.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.tool.adapter.ToolAdapterContext;
import dev.horizen.agent.tool.adapter.ToolCatalogSnapshot;
import dev.horizen.agent.tool.adapter.ToolDefinition;
import dev.horizen.agent.tool.adapter.ToolProvider;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class EvaluationToolProviderReplayPolicyTest {
    @Test
    void captureOnlyInvokesMissingTrustedReadsAndNeverInvokesWritesOrUnknownTools() {
        AtomicInteger live = new AtomicInteger();
        ToolProvider delegate =
                new ToolProvider() {
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
                        return Mono.just(List.of());
                    }

                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext context,
                            String id,
                            String tool,
                            Map<String, Object> args) {
                        assertEquals("query", tool);
                        live.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("live read"));
                    }
                };
        var fixture =
                new EvaluationFixture(
                        Map.of(
                                "modeLabel",
                                "CAPTURE_READONLY",
                                "entries",
                                List.of(
                                        Map.of(
                                                "tool",
                                                "write",
                                                "args",
                                                Map.of("value", 1),
                                                "result",
                                                "recorded write"))));
        fixture.liveLimits(Map.of("write", 0, "query", 1));
        var runtime = RuntimeContext.builder().build();
        runtime.put(EvaluationFixture.class, fixture);
        runtime.put(
                EvaluationToolProvider.class.getName(),
                ToolCatalogSnapshot.class,
                new ToolCatalogSnapshot(
                        "adapter",
                        "turn",
                        Instant.now(),
                        List.of(
                                new ToolDefinition(
                                        "query",
                                        "",
                                        Map.of("type", "object"),
                                        true,
                                        "low",
                                        30,
                                        true,
                                        true,
                                        false,
                                        "none",
                                        null),
                                new ToolDefinition(
                                        "write",
                                        "",
                                        Map.of("type", "object"),
                                        false,
                                        "low",
                                        30,
                                        true,
                                        true,
                                        false,
                                        "none",
                                        null))));
        var context = new ToolAdapterContext("owner", "session", "turn", runtime);
        var provider = new EvaluationToolProvider(delegate);
        provider.invoke(context, "a", "query", Map.of("id", 1)).block();
        provider.invoke(context, "b", "query", Map.of("id", 1)).block();
        assertEquals(1, live.get());
        assertNull(fixture.getFailure());
        provider.invoke(context, "c", "write", Map.of("value", 1)).block();
        assertEquals(1, live.get());
        assertEquals(2, fixture.recorded().size());
        provider.invoke(context, "d", "write", Map.of("value", 2)).block();
        assertEquals("FIXTURE_MISS", fixture.getFailure());
        assertEquals(1, live.get());
        provider.invoke(context, "e", "unknown", Map.of()).block();
        assertEquals(1, live.get());
    }

    @Test
    void relaxedReusableReadsRequireTheCurrentTrustedCatalogAndNeverInvokeLiveProvider() {
        AtomicInteger live = new AtomicInteger();
        ToolProvider delegate =
                new ToolProvider() {
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
                        return Mono.just(List.of());
                    }

                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext context,
                            String id,
                            String tool,
                            Map<String, Object> args) {
                        live.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("live"));
                    }
                };
        for (boolean authorizedRead : new boolean[] {true, false}) {
            var fixture =
                    new EvaluationFixture(
                            Map.of(
                                    "modeLabel",
                                    "REPLAY",
                                    "entries",
                                    List.of(
                                            Map.of(
                                                    "tool",
                                                    "search_docs",
                                                    "args",
                                                    Map.of("query", "initial"),
                                                    "ignoredArgs",
                                                    List.of("query"),
                                                    "reusable",
                                                    true,
                                                    "result",
                                                    "frozen"))));
            var runtime = RuntimeContext.builder().build();
            runtime.put(EvaluationFixture.class, fixture);
            var definition =
                    new ToolDefinition(
                            "search_docs",
                            "",
                            Map.of("type", "object"),
                            authorizedRead,
                            "low",
                            30,
                            true,
                            true,
                            false,
                            "none",
                            null);
            runtime.put(
                    EvaluationToolProvider.class.getName(),
                    ToolCatalogSnapshot.class,
                    new ToolCatalogSnapshot("adapter", "turn", Instant.now(), List.of(definition)));
            var context = new ToolAdapterContext("owner", "session", "turn", runtime);
            var provider = new EvaluationToolProvider(delegate);
            provider.invoke(context, "call1", "search_docs", Map.of("query", "changed")).block();
            provider.invoke(context, "call2", "search_docs", Map.of("query", "changed")).block();
            if (authorizedRead) assertNull(fixture.getFailure());
            else assertEquals("FIXTURE_MISS", fixture.getFailure());
        }
        assertEquals(0, live.get());
    }
}
