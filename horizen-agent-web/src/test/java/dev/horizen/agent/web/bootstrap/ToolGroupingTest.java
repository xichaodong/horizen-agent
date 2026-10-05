package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.tool.adapter.ToolDefinition;
import dev.horizen.agent.tool.adapter.ToolGroupDefinition;
import dev.horizen.agent.web.bootstrap.runtime.AgentToolRegistry;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ToolGroupingTest {
    @Test
    void providerDeclaresGroupsWithoutHorizenInterpretingToolOrSkillNames() {
        Toolkit toolkit = new Toolkit();
        AgentToolRegistry.createToolGroups(toolkit);
        ToolDefinition common = definition("catalog_lookup", ToolGroupDefinition.externalDefault());
        ToolDefinition skillBound =
                definition(
                        "domain_diagnose",
                        new ToolGroupDefinition(
                                "domain-diagnostics",
                                "Provider-owned diagnostics",
                                false,
                                "domain-skill"));
        for (ToolDefinition definition : List.of(common, skillBound)) {
            AgentToolRegistry.ensureProviderToolGroup(toolkit, definition.getGroup());
            toolkit.registerAgentTool(new DummyTool(definition.getName()));
            toolkit.addToolToGroup(definition.getGroup().getId(), definition.getName());
        }
        List<String> active = toolkit.getActiveGroups();
        assertTrue(names(toolkit, active).contains("catalog_lookup"));
        assertFalse(names(toolkit, active).contains("domain_diagnose"));
        List<String> activated = new ArrayList<>(active);
        activated.add("domain-diagnostics");
        assertTrue(
                names(toolkit, activated)
                        .containsAll(List.of("catalog_lookup", "domain_diagnose")));
    }

    @Test
    void providerCannotClaimHostReservedGroups() {
        Toolkit toolkit = new Toolkit();
        AgentToolRegistry.createToolGroups(toolkit);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgentToolRegistry.ensureProviderToolGroup(
                                toolkit, new ToolGroupDefinition("core", "override", true, "")));
    }

    private static ToolDefinition definition(String name, ToolGroupDefinition group) {
        return new ToolDefinition(
                name,
                "test",
                Map.of("type", "object", "properties", Map.of()),
                true,
                "low",
                30,
                true,
                true,
                false,
                "none",
                group);
    }

    private static List<String> names(Toolkit toolkit, List<String> groups) {
        return toolkit.getToolSchemas(groups).stream().map(ToolSchema::getName).toList();
    }

    private static final class DummyTool extends ToolBase {
        DummyTool(String name) {
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
