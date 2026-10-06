package dev.horizen.agent.tool.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.tool.adapter.ToolAdapter;
import dev.horizen.agent.tool.adapter.ToolAdapterContext;
import dev.horizen.agent.tool.adapter.ToolCatalogResolver;
import dev.horizen.agent.tool.adapter.ToolDefinition;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

class ToolGovernanceTest {
    @Test
    void filtersUnavailableToolsAndCapturesExactView() {
        ToolDescriptorRegistry registry = new ToolDescriptorRegistry();
        registry.register(descriptor("visible"));
        registry.register(descriptor("hidden"));
        registry.setAvailability("hidden", false, "missing_provider");
        ToolViewMiddleware middleware = new ToolViewMiddleware(registry);
        RuntimeContext context =
                RuntimeContext.builder().userId("owner").sessionId("session").build();
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();
        middleware
                .onReasoning(
                        null,
                        context,
                        new ReasoningInput(
                                List.of(),
                                List.of(schema("visible"), schema("hidden")),
                                GenerateOptions.builder().build()),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .blockLast();
        assertEquals(
                List.of("visible"),
                captured.get().tools().stream().map(ToolSchema::getName).toList());
        ToolViewSnapshot snapshot = context.get(ToolViewSnapshot.class);
        assertTrue(snapshot.allows("visible"));
        assertFalse(snapshot.allows("hidden"));
        assertEquals(ToolViewMiddleware.version(Set.of("visible")), snapshot.getVersion());
        assertNotEquals(snapshot.getVersion(), ToolViewMiddleware.version(Set.of("hidden")));
    }

    @Test
    void skillGroupControlsModelSchemas() {
        Toolkit toolkit = new Toolkit();
        toolkit.createSkillToolGroup("domain", "domain", false, "domain-diagnosis");
        toolkit.registerAgentTool(new NoopTool("core_tool"));
        toolkit.registerAgentTool(new NoopTool("domain_tool"));
        toolkit.addToolToGroup("domain", "domain_tool");
        assertEquals(
                List.of("core_tool"),
                toolkit.getToolSchemas(List.of()).stream().map(ToolSchema::getName).toList());
        assertTrue(
                toolkit.getToolSchemas(List.of("domain")).stream()
                        .map(ToolSchema::getName)
                        .toList()
                        .containsAll(List.of("core_tool", "domain_tool")));
    }

    @Test
    void rejectsCallsOutsideLatestSnapshot() {
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("session")
                        .put(
                                ToolViewSnapshot.class,
                                new ToolViewSnapshot(
                                        "owner",
                                        "session",
                                        "turn",
                                        "v1",
                                        Instant.now(),
                                        Set.of("allowed")))
                        .build();
        ToolViewMiddleware middleware = new ToolViewMiddleware();
        IllegalStateException error =
                Assertions.assertThrows(
                        IllegalStateException.class,
                        () ->
                                middleware
                                        .onActing(
                                                null,
                                                context,
                                                new ActingInput(
                                                        List.of(
                                                                new ToolUseBlock(
                                                                        "call", "denied",
                                                                        Map.of()))),
                                                next -> Flux.empty())
                                        .blockLast());
        assertTrue(error.getMessage().contains("denied"));
    }

    @Test
    void dynamicProviderCatalogFiltersExternalToolsPerOwner() {
        ToolDescriptorRegistry registry = new ToolDescriptorRegistry();
        registry.register(
                new ToolDescriptor(
                        "domain_lookup",
                        "external",
                        true,
                        "low",
                        30,
                        true,
                        true,
                        false,
                        "none",
                        true));
        ToolAdapter adapter =
                new ToolAdapter() {
                    @Override
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
                        return Mono.just(
                                "owner-a".equals(context.getOwnerKey())
                                        ? List.of(
                                        new ToolDefinition(
                                                "domain_lookup",
                                                "",
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        Map.of()),
                                                "low",
                                                false))
                                        : List.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext context,
                            String id,
                            String name,
                            Map<String, Object> input) {
                        return Mono.empty();
                    }
                };
        ToolViewMiddleware middleware =
                new ToolViewMiddleware(registry, List.of(new ToolCatalogResolver(adapter)));
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("owner-b")
                        .sessionId("session")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner-b", "session", "turn"))
                        .build();
        middleware
                .onAgent(null, context, new AgentInput(List.<Msg>of()), next -> Flux.empty())
                .blockLast();
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();
        middleware
                .onReasoning(
                        null,
                        context,
                        new ReasoningInput(
                                List.of(),
                                List.of(schema("domain_lookup")),
                                GenerateOptions.builder().build()),
                        next -> {
                            captured.set(next);
                            return Flux.empty();
                        })
                .blockLast();
        assertTrue(captured.get().tools().isEmpty());
    }

    private static ToolDescriptor descriptor(String name) {
        return new ToolDescriptor(name, "core", true, "low", 30, true, true, false, "none");
    }

    private static ToolSchema schema(String name) {
        return ToolSchema.builder()
                .name(name)
                .description("")
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .build();
    }

    private static final class NoopTool extends ToolBase {
        NoopTool(String name) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description("")
                            .inputSchema(Map.of("type", "object", "properties", Map.of())));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("ok"));
        }
    }
}
