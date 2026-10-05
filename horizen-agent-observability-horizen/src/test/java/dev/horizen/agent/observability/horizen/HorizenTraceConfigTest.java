package dev.horizen.agent.observability.horizen;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class HorizenTraceConfigTest {
    @Test
    void disabledConfigurationIsAbsent() {
        assertTrue(HorizenTraceConfig.fromEnvironment(Map.of()).isEmpty());
    }

    @Test
    void readsEnvironmentNamesWithoutDefaultingToPrivateEndpoints() {
        var config =
                HorizenTraceConfig.fromEnvironment(
                                Map.of(
                                        "HORIZEN_TRACE_ENABLED", "true",
                                        "HORIZEN_BASE_URL", "http://localhost:8080/proxy?env=test",
                                        "HORIZEN_PROJECT_ID", "7",
                                        "HORIZEN_TRACE_TOKEN", "test-token",
                                        "HORIZEN_EXECUTOR_NAME", "horizen-agent",
                                        "HORIZEN_ENVIRONMENT", "offline",
                                        "HORIZEN_AGENT_NAME", "horizen",
                                        "HORIZEN_TRACE_CONTENT", "true"))
                        .orElseThrow();
        assertEquals(7L, config.getProjectId());
        assertEquals("http://localhost:8080/proxy?env=test", config.getBaseUrl().toString());
        assertEquals("test-token", config.getAuthToken());
        assertEquals("horizen-agent", config.getExecutorName());
        assertEquals("offline", config.getEnvironment());
        assertEquals("horizen", config.getAgentName());
        assertTrue(config.isCaptureContent());
    }

    @Test
    void enabledConfigurationRequiresDestinationAndProject() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HorizenTraceConfig.fromEnvironment(Map.of("HORIZEN_TRACE_ENABLED", "true")));
    }
}
