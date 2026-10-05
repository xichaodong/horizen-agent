package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SandboxSnapshotPropertiesTest {
    @Test
    void validatesCredentialsAndBoundsBeforeEnablingRemoteTransfers() {
        var properties = new SandboxSnapshotProperties();
        assertFalse(properties.isEnabled());
        assertThrows(IllegalStateException.class, properties::toConfig);
        properties.setEnabled(true);
        assertThrows(IllegalArgumentException.class, properties::toConfig);
        properties.setEndpoint("https://bos.example.test");
        properties.setBucket("fixture");
        properties.setAccessKey("fixture-ak");
        properties.setSecretKey("fixture-sk");
        assertEquals(64L * 1024 * 1024, properties.toConfig().getMaxObjectBytes());
        assertEquals("agentFiles/horizen-sandbox-snapshots", properties.toConfig().getKeyPrefix());
        properties.setConcurrency(3);
        assertThrows(IllegalArgumentException.class, properties::toConfig);
    }
}
