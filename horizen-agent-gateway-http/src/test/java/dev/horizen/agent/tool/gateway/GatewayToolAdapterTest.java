package dev.horizen.agent.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.adapter.gateway.fixture.FixtureGateway;
import dev.horizen.agent.tool.adapter.ToolAdapterContext;
import dev.horizen.agent.tool.adapter.ToolDefinition;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class GatewayToolAdapterTest {
    @TempDir Path directory;

    @Test
    void convertsExistingGatewayCatalogToConcreteDefinitionsAndInvokesByName() throws Exception {
        Path fixture = directory.resolve("tools.json");
        Files.writeString(
                fixture,
                """
{"tools":[{"name":"domain_lookup","description":"Find a domain object",
"inputSchema":{"type":"object","properties":{"spuId":{"type":"string"}},"required":["spuId"]},
"readOnly":true,"timeoutSeconds":12,"idempotent":true,
"concurrencySafe":false,"supportsCancellation":true,"approvalPolicy":"required",
"group":{"id":"domain-read","description":"Domain read tools",
    "activeByDefault":false,"activateOnSkill":"domain-skill"},
"response":{"title":"Demo"}}]}
""");
        GatewayToolAdapter adapter = new GatewayToolAdapter(new FixtureGateway(fixture));
        RuntimeContext runtime =
                RuntimeContext.builder().userId("owner-1").sessionId("session-1").build();

        List<ToolDefinition> definitions = adapter.list(ToolAdapterContext.from(runtime)).block();
        ToolDefinition definition = definitions.get(0);
        assertEquals("domain_lookup", definition.getName());
        assertEquals("object", definition.getInputSchema().get("type"));
        assertTrue(definition.isReadOnly());
        assertTrue(definition.requiresApproval());
        assertEquals(12, definition.getTimeoutSeconds());
        assertTrue(definition.isIdempotent());
        assertFalse(definition.isConcurrencySafe());
        assertTrue(definition.isSupportsCancellation());
        assertEquals("domain-read", definition.getGroup().getId());
        assertEquals("domain-skill", definition.getGroup().getActivateOnSkill());

        ToolResultBlock result =
                adapter.invoke(
                                ToolAdapterContext.from(runtime),
                                "call-1",
                                definition.getName(),
                                Map.of("spuId", "1001"))
                        .block();
        assertTrue(((TextBlock) result.getOutput().get(0)).getText().contains("Demo"));
    }

    @Test
    void doesNotInferApprovalFromRiskLevel() throws Exception {
        Path fixture = directory.resolve("high-risk-tools.json");
        Files.writeString(
                fixture,
                """
        {"tools":[{"name":"coupon_create","riskLevel":"high",
        "inputSchema":{"type":"object","properties":{}},"response":{}}]}
        """);
        GatewayToolAdapter adapter = new GatewayToolAdapter(new FixtureGateway(fixture));

        ToolDefinition definition =
                adapter.list(ToolAdapterContext.from(RuntimeContext.empty())).block().get(0);

        assertFalse(definition.requiresApproval());
    }
}
