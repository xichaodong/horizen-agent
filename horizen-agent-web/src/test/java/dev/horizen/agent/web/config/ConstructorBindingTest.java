package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

class ConstructorBindingTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsOverridesAndDefaultsIntoReadonlyProperties() {
        runner.withPropertyValues(
                        "horizen.agent.sandbox.e2b.enabled=true",
                        "horizen.agent.sandbox.e2b.api-key=synthetic-key",
                        "horizen.agent.sandbox.e2b.api-base-url=https://api.example.test",
                        "horizen.agent.sandbox.e2b.runtime-base-url-pattern=https://{sandbox_id}.example.test",
                        "horizen.agent.sandbox.e2b.template-id=template",
                        "horizen.agent.sandbox.e2b.connect-timeout-seconds=7",
                        "horizen.agent.gateway.mode=MOCK",
                        "horizen.agent.gateway.allowed-tools=search,lookup",
                        "horizen.trace.max-retries=3")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var sandbox = context.getBean(E2bSandboxProperties.class);
                            assertTrue(sandbox.isEnabled());
                            assertEquals(7, sandbox.getConnectTimeoutSeconds());
                            assertEquals(300, sandbox.getSandboxTimeoutSeconds());
                            assertFalse(sandbox.toString().contains("synthetic-key"));
                            var gateway = context.getBean(GatewayProperties.class);
                            assertTrue(gateway.mock());
                            assertEquals(2, gateway.getAllowedTools().size());
                            assertThrows(
                                    UnsupportedOperationException.class,
                                    () -> gateway.getAllowedTools().add("bad"));
                            assertEquals(
                                    3, context.getBean(HorizenProperties.class).getMaxRetries());
                            for (Class<?> type :
                                    List.of(
                                            E2bSandboxProperties.class,
                                            GatewayProperties.class,
                                            HorizenProperties.class,
                                            ArtifactProperties.class)) {
                                assertTrue(
                                        Arrays.stream(type.getMethods())
                                                .noneMatch(
                                                        method ->
                                                                method.getName()
                                                                        .startsWith("set")));
                            }
                        });
    }

    @Test
    void rejectsInvalidValuesAtConfigurationBindingBoundary() {
        runner.withPropertyValues("horizen.agent.sandbox.e2b.connect-timeout-seconds=0")
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("horizen.agent.sandbox.e2b.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            E2bSandboxProperties.class,
            GatewayProperties.class,
            HorizenProperties.class,
            ArtifactProperties.class
    })
    static class PropertiesConfiguration {
    }
}
