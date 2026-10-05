package dev.horizen.agent.tool.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class ToolCatalogResolverTest {
    @Test
    void loadsOnceForOneTurnAndNeverMixesAnotherTurnSnapshot() {
        AtomicInteger calls = new AtomicInteger();
        ToolAdapter adapter =
                new ToolAdapter() {
                    @Override
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
                        calls.incrementAndGet();
                        return Mono.just(
                                List.of(
                                        definition(
                                                "lookup_"
                                                        + context.getTurnId().replace('-', '_'))));
                    }

                    @Override
                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext c,
                            String id,
                            String name,
                            Map<String, Object> input) {
                        return Mono.just(ToolResultBlock.text("unused"));
                    }
                };
        RuntimeContext runtime =
                RuntimeContext.builder().userId("owner-a").sessionId("session-a").build();
        ToolCatalogResolver resolver =
                new ToolCatalogResolver(
                        adapter,
                        "test-adapter",
                        Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC));
        ToolAdapterContext first =
                new ToolAdapterContext("owner-a", "session-a", "turn-a", runtime);
        ToolAdapterContext second =
                new ToolAdapterContext("owner-a", "session-a", "turn-b", runtime);

        assertEquals(
                "lookup_turn_a", resolver.resolve(first).block().getDefinitions().get(0).getName());
        assertEquals(
                "lookup_turn_a", resolver.resolve(first).block().getDefinitions().get(0).getName());
        assertEquals(
                "lookup_turn_b",
                resolver.resolve(second).block().getDefinitions().get(0).getName());
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsDuplicateModelVisibleNames() {
        ToolAdapter adapter =
                new ToolAdapter() {
                    @Override
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext c) {
                        return Mono.just(List.of(definition("same_tool"), definition("same_tool")));
                    }

                    @Override
                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext c,
                            String id,
                            String name,
                            Map<String, Object> input) {
                        return Mono.empty();
                    }
                };
        RuntimeContext runtime = RuntimeContext.builder().build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ToolCatalogResolver(adapter, "duplicates", Clock.systemUTC())
                                .resolve(
                                        new ToolAdapterContext("owner", "session", "turn", runtime))
                                .block());
    }

    private static ToolDefinition definition(String name) {
        return new ToolDefinition(
                name, "test", Map.of("type", "object", "properties", Map.of()), "low", false);
    }
}
