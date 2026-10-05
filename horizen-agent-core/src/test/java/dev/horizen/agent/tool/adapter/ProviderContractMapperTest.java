package dev.horizen.agent.tool.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.provider.spi.ApprovalPolicy;
import dev.horizen.agent.provider.spi.RiskLevel;
import dev.horizen.agent.provider.spi.ToolContract;
import dev.horizen.agent.provider.spi.ToolGroupContract;

import org.junit.jupiter.api.Test;

import java.util.Map;

class ProviderContractMapperTest {
    @Test
    void preservesEveryGovernanceFieldAcrossRuntimeBoundary() {
        ToolContract contract =
                new ToolContract(
                        "domain_write",
                        "write",
                        Map.of("type", "object", "properties", Map.of()),
                        false,
                        RiskLevel.HIGH,
                        47,
                        true,
                        false,
                        true,
                        ApprovalPolicy.REQUIRED,
                        new ToolGroupContract("domain-write", "writes", false, "domain-skill"));

        ToolDefinition definition = ProviderContractMapper.toDefinition(contract);
        ToolContract restored = ProviderContractMapper.toContract(definition);

        assertEquals("high", definition.getRiskLevel());
        assertTrue(definition.requiresApproval());
        assertEquals(47, definition.getTimeoutSeconds());
        assertTrue(definition.isIdempotent());
        assertTrue(definition.isSupportsCancellation());
        assertEquals("domain-skill", definition.getGroup().getActivateOnSkill());
        assertEquals(contract.validate(), restored);
    }
}
