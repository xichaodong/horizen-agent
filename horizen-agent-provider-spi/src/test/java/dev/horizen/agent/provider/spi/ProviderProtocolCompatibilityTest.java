package dev.horizen.agent.provider.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ProviderProtocolCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void protocolEnumsKeepTheirLowercaseScalarWireValues() throws Exception {
        Map<Enum<?>, String> expected =
                Map.ofEntries(
                        Map.entry(ApprovalPolicy.NONE, "none"),
                        Map.entry(ApprovalPolicy.REQUIRED, "required"),
                        Map.entry(CatalogScope.REGISTRATION, "registration"),
                        Map.entry(CatalogScope.TURN, "turn"),
                        Map.entry(RiskLevel.LOW, "low"),
                        Map.entry(RiskLevel.MEDIUM, "medium"),
                        Map.entry(RiskLevel.HIGH, "high"),
                        Map.entry(RiskLevel.CRITICAL, "critical"),
                        Map.entry(ProviderResultStatus.SUCCESS, "success"),
                        Map.entry(ProviderResultStatus.ERROR, "error"),
                        Map.entry(ProviderResultStatus.DENIED, "denied"),
                        Map.entry(ProviderResultStatus.CANCELLED, "cancelled"));
        for (var entry : expected.entrySet()) {
            String wire = JSON.writeValueAsString(entry.getKey());
            assertEquals("\"" + entry.getValue() + "\"", wire);
            assertEquals(entry.getKey(), JSON.readValue(wire, entry.getKey().getDeclaringClass()));
        }
    }

    @Test
    void roundTripsVersionOneCatalogWithoutRuntimeTypes() throws Exception {
        ToolContract tool =
                new ToolContract(
                        "domain_lookup",
                        "lookup",
                        Map.of("type", "object", "properties", Map.of()),
                        true,
                        RiskLevel.LOW,
                        12,
                        true,
                        true,
                        false,
                        ApprovalPolicy.NONE,
                        new ToolGroupContract("domain-read", "read tools", false, "domain-skill"));
        ProviderCatalogResponse response =
                new ProviderCatalogResponse(1, "catalog-v7", List.of(tool)).validate();

        String json = JSON.writeValueAsString(response);
        ProviderCatalogResponse restored =
                JSON.readValue(json, ProviderCatalogResponse.class).validate();

        assertEquals("catalog-v7", restored.getCatalogVersion());
        assertEquals(RiskLevel.LOW, restored.getTools().get(0).getRiskLevel());
        assertEquals("domain-skill", restored.getTools().get(0).getGroup().getActivateOnSkill());
        assertTrue(json.contains("\"approvalPolicy\":\"none\""));
    }

    @Test
    void roundTripsTurnContextAndStructuredResult() throws Exception {
        ProviderCatalogRequest catalog =
                new ProviderCatalogRequest(1, CatalogScope.TURN, "owner", "session", "turn")
                        .validate();
        assertEquals(
                "turn", JSON.readTree(JSON.writeValueAsString(catalog)).path("scope").asText());

        ProviderInvokeRequest invoke =
                new ProviderInvokeRequest(
                                1,
                                "owner",
                                "session",
                                "turn",
                                "call",
                                "domain_lookup",
                                Map.of("id", "1"))
                        .validate();
        assertEquals(
                "owner",
                JSON.readValue(JSON.writeValueAsString(invoke), ProviderInvokeRequest.class)
                        .validate()
                        .getOwnerKey());

        PresentationContract presentation =
                new PresentationContract(
                        1,
                        List.of(
                                new PresentationBlockContract(
                                        "conclusion", 1, Map.of("title", "complete"))));
        ProviderInvokeResponse result =
                new ProviderInvokeResponse(
                                1,
                                ProviderResultStatus.SUCCESS,
                                Map.of("summary", "ok"),
                                null,
                                null,
                                presentation,
                                List.of(
                                        new ArtifactReferenceContract(
                                                "art_1", "report.md", "text/markdown", 12L)))
                        .validate();
        ProviderInvokeResponse restored =
                JSON.readValue(JSON.writeValueAsString(result), ProviderInvokeResponse.class)
                        .validate();
        assertEquals(
                "complete", restored.getPresentation().getBlocks().get(0).getData().get("title"));
        assertEquals("art_1", restored.getArtifacts().get(0).getArtifactId());
    }

    @Test
    void rejectsUnsupportedVersionsLateApprovalAndIncompleteTurnIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProviderCatalogRequest(2, CatalogScope.REGISTRATION, "", "", "")
                                .validate());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProviderCatalogRequest(1, CatalogScope.TURN, "owner", "", "turn")
                                .validate());
        assertThrows(
                Exception.class,
                () ->
                        JSON.readValue(
                                "{\"protocolVersion\":1,\"status\":\"approval_required\"}",
                                ProviderInvokeResponse.class));
    }
}
