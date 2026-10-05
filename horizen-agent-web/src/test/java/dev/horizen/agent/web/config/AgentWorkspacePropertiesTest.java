package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.Set;

class AgentWorkspacePropertiesTest {
    @Test
    void tokenIsOptionalForInternalDirectCalls() {
        AgentWorkspaceProperties properties = new AgentWorkspaceProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://platform.example/internal/releases/current");
        properties.setProjectId(7);
        properties.setArtifactHosts(Set.of("assets.example"));
        properties.setAllowHttp(true);
        assertDoesNotThrow(properties::validate);
        properties.setProjectId(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
