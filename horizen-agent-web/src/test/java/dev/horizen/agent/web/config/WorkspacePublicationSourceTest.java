package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkspacePublicationSourceTest {
    @Test
    void runtimeStaysOnRemoteSourceUntilExplicitLocalCutover() {
        var properties = new AgentWorkspaceProperties();
        assertEquals(AgentWorkspaceProperties.Source.REMOTE, properties.getSource());
        properties.setEnabled(true);
        properties.setProjectId(7);
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setSource(AgentWorkspaceProperties.Source.LOCAL);
        assertDoesNotThrow(properties::validate);
        assertTrue(properties.getEndpoint().isEmpty());
        assertTrue(properties.getArtifactHosts().isEmpty());
    }
}
