package dev.horizen.agent.tool.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.domain.presentation.PresentationEventCollector;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class ToolAdapterToolsTest {

    @Test
    void exposesDirectBusinessSchemasAndForwardsTrustedRuntimeContext() {
        AtomicReference<ToolAdapterContext> seenContext = new AtomicReference<>();
        AtomicReference<String> seenCallId = new AtomicReference<>();
        ToolAdapter adapter =
                new ToolAdapter() {
                    @Override
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
                        return Mono.just(List.of(definition("product_lookup", false)));
                    }

                    @Override
                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext context,
                            String callId,
                            String name,
                            Map<String, Object> input) {
                        seenContext.set(context);
                        seenCallId.set(callId);
                        assertEquals("product_lookup", name);
                        assertEquals(Map.of("spuId", "1001"), input);
                        return Mono.just(ToolResultBlock.text("ok"));
                    }
                };
        Toolkit toolkit = new Toolkit();
        ToolAdapterTools.register(toolkit, adapter, List.of(definition("product_lookup", false)));

        assertTrue(toolkit.getToolNames().contains("product_lookup"));
        assertFalse(toolkit.getToolNames().contains("tool_gateway_list"));
        assertFalse(toolkit.getToolNames().contains("tool_gateway_invoke"));

        RuntimeContext runtime =
                RuntimeContext.builder().userId("owner-1").sessionId("session-1").build();
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .runtimeContext(runtime)
                                        .input(Map.of("spuId", "1001"))
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("call-1")
                                                        .name("product_lookup")
                                                        .input(Map.of("spuId", "1001"))
                                                        .content("{}")
                                                        .build())
                                        .build())
                        .block();

        assertEquals("ok", ((TextBlock) result.getOutput().get(0)).getText());
        assertEquals("owner-1", seenContext.get().getOwnerKey());
        assertEquals("session-1", seenContext.get().getSessionId());
        assertEquals("call-1", seenCallId.get());
    }

    @Test
    void catalogApprovalRequirementBecomesNativePermissionAsk() {
        Toolkit toolkit = new Toolkit();
        ToolAdapterTools.register(
                toolkit, new NoopAdapter(), List.of(definition("coupon_create", true)));

        ToolBase tool = (ToolBase) toolkit.getTool("coupon_create");
        PermissionDecision decision = tool.checkPermissions(Map.of(), null).block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
    }

    @Test
    void rechecksTurnCatalogBeforeBusinessExecution() {
        AtomicBoolean invoked = new AtomicBoolean();
        ToolAdapter adapter =
                new ToolAdapter() {
                    @Override
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
                        return Mono.just(List.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext context,
                            String id,
                            String name,
                            Map<String, Object> input) {
                        invoked.set(true);
                        return Mono.just(ToolResultBlock.text("bad"));
                    }
                };
        Toolkit toolkit = new Toolkit();
        ToolAdapterTools.register(toolkit, adapter, List.of(definition("product_lookup", false)));
        RuntimeContext runtime =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("session")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner", "session", "turn"))
                        .build();
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .runtimeContext(runtime)
                                        .input(Map.of())
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("call")
                                                        .name("product_lookup")
                                                        .input(Map.of())
                                                        .content("{}")
                                                        .build())
                                        .build())
                        .block();
        String output = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(output.contains("revoked"), output);
        assertFalse(invoked.get());
    }

    @Test
    void collectsExplicitPresentationWithoutChangingToolResult() {
        ToolAdapter adapter =
                new ToolAdapter() {
                    @Override
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
                        return Mono.just(List.of(definition("diagnose", false)));
                    }

                    @Override
                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext context,
                            String id,
                            String name,
                            Map<String, Object> input) {
                        return Mono.just(
                                ToolResultBlock.text(
                                        """
                                                {"safeResult":{"summary":"ok"},"presentation":{"schemaVersion":1,
                                                "blocks":[{"type":"conclusion","title":"诊断完成"}]}}
                                                """));
                    }
                };
        Toolkit toolkit = new Toolkit();
        ToolAdapterTools.register(toolkit, adapter, List.of(definition("diagnose", false)));
        PresentationEventCollector collector = new PresentationEventCollector();
        RuntimeContext runtime =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("session")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner", "session", "turn"))
                        .put(PresentationEventCollector.class, collector)
                        .build();

        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .runtimeContext(runtime)
                                        .input(Map.of())
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("call")
                                                        .name("diagnose")
                                                        .input(Map.of())
                                                        .content("{}")
                                                        .build())
                                        .build())
                        .block();

        assertTrue(((TextBlock) result.getOutput().get(0)).getText().contains("summary"));
        assertEquals("conclusion", collector.drain("call").get(0).getType());
    }

    @Test
    void refusesPerTurnContractChangesInsteadOfBypassingNewApprovalPolicy() {
        ToolDefinition registered = definition("domain_write", false);
        ToolAdapter adapter =
                new ToolAdapter() {
                    @Override
                    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
                        return Mono.just(List.of(definition("domain_write", true)));
                    }

                    @Override
                    public Mono<ToolResultBlock> invoke(
                            ToolAdapterContext context,
                            String id,
                            String name,
                            Map<String, Object> input) {
                        return Mono.just(ToolResultBlock.text("must-not-run"));
                    }
                };
        Toolkit toolkit = new Toolkit();
        ToolAdapterTools.register(toolkit, adapter, List.of(registered));
        RuntimeContext runtime =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("session")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner", "session", "turn"))
                        .build();

        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .runtimeContext(runtime)
                                        .input(Map.of())
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("call")
                                                        .name("domain_write")
                                                        .input(Map.of())
                                                        .content("{}")
                                                        .build())
                                        .build())
                        .block();

        assertTrue(((TextBlock) result.getOutput().get(0)).getText().contains("contract_changed"));
    }

    private static ToolDefinition definition(String name, boolean requiresApproval) {
        return new ToolDefinition(
                name,
                "test",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("spuId", Map.of("type", "string")),
                        "additionalProperties",
                        false),
                requiresApproval ? "high" : "low",
                requiresApproval);
    }

    private static final class NoopAdapter implements ToolAdapter {
        @Override
        public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<ToolResultBlock> invoke(
                ToolAdapterContext context,
                String toolCallId,
                String toolName,
                Map<String, Object> input) {
            return Mono.just(ToolResultBlock.text("unused"));
        }
    }
}
