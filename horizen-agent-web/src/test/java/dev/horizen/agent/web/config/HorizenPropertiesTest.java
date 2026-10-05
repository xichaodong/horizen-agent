package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class HorizenPropertiesTest {
    @Test
    void disabledTracingNeedsNoEndpoint() {
        HorizenProperties properties =
                new HorizenProperties(false, null, null, null, false, null, null, null, null);

        assertNull(properties.toConfig());
    }

    @Test
    void buildsContentCapturingExporterConfigWithoutExposingToken() {
        HorizenProperties properties =
                new HorizenProperties(
                        true,
                        "http://127.0.0.1:8080/horizen",
                        7L,
                        "trace-test-token",
                        true,
                        Duration.ofSeconds(2),
                        Duration.ZERO,
                        32,
                        Duration.ofSeconds(2));

        var config = properties.toConfig();
        assertEquals(7L, config.getProjectId());
        assertTrue(config.isCaptureContent());
        assertFalse(properties.toString().contains("trace-test-token"));
        assertEquals("Bearer ***", properties.redact("Bearer trace-test-token"));
    }

    @Test
    void deploymentIdentityAndRetryPolicyAreConfigurable() {
        HorizenProperties properties =
                new HorizenProperties(
                        true,
                        "http://localhost/horizen",
                        7L,
                        "test-token",
                        false,
                        null,
                        null,
                        null,
                        null,
                        "test-source",
                        "worker",
                        "executor-1",
                        "test",
                        3,
                        Duration.ofMillis(25),
                        Duration.ofMillis(100));
        var config = properties.toConfig();
        assertEquals("test-source", config.getSource());
        assertEquals("worker", config.getAgentName());
        assertEquals("executor-1", config.getExecutorName());
        assertEquals("test", config.getEnvironment());
        assertEquals(3, config.getMaxRetries());
    }

    @Test
    void enabledTracingRequiresEndpointAndProject() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HorizenProperties(true, "", 7L, null, false, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new HorizenProperties(
                                true,
                                "http://127.0.0.1:8080",
                                0L,
                                null,
                                false,
                                null,
                                null,
                                null,
                                null));
    }
}
