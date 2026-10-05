package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.adapter.gateway.fixture.FixtureGateway;
import dev.horizen.agent.tool.gateway.GatewayToolAdapter;
import dev.horizen.agent.web.bootstrap.runtime.AgentToolRegistry;
import dev.horizen.agent.web.config.GatewayProperties;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

class MockGatewayWiringTest {
    @TempDir Path directory;

    @Test
    void mockModeIgnoresRemoteConfigurationAndNeedsNoSessionOrToken() throws Exception {
        Path fixture = directory.resolve("fixture.json");
        Files.writeString(
                fixture,
                """
        {"tools":[{"name":"inspect_sample","description":"Mock sample",
          "inputSchema":{"type":"object","additionalProperties":false},
          "response":{"count":3}}]}
        """);
        GatewayProperties properties =
                new GatewayProperties(
                        "unused-invalid-remote-url",
                        "",
                        null,
                        Set.of("different_remote_tool"),
                        GatewayProperties.Mode.MOCK,
                        fixture);

        var gateway = AgentToolRegistry.createGateway(properties);
        assertInstanceOf(FixtureGateway.class, gateway);
        var result =
                new GatewayToolAdapter(gateway)
                        .invokeGateway(null, "mock-call", "inspect_sample", Map.of())
                        .block();
        assertEquals(ToolResultState.SUCCESS, result.getState());
        String text =
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .findFirst()
                        .orElseThrow();
        assertTrue(text.contains("\"mock\":true"));
        assertTrue(text.contains("\"count\":3"));
    }
}
