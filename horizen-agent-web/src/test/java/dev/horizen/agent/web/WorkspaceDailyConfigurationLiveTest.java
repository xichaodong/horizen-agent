package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.web.config.ArtifactProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.RuntimeStorageProperties;
import dev.horizen.agent.web.config.SandboxSnapshotProperties;

import io.agentscope.harness.agent.IsolationScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 仅绑定配置，不实例化 AgentService，也不调用外部模型或 Provider。
 */
@EnabledIfSystemProperty(named = "horizen.workspace.config.live", matches = "true")
class WorkspaceDailyConfigurationLiveTest {
    @Test
    void dailyConfigurationEnablesSessionSnapshotsAndReusesExistingCredentials() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ConfigurationOnly.class)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var snapshot = context.getBean(SandboxSnapshotProperties.class);
                            var sandbox = context.getBean(E2bSandboxProperties.class);
                            var artifact = context.getBean(ArtifactProperties.class).toConfig();
                            assertTrue(snapshot.isEnabled());
                            assertTrue(sandbox.isEnabled());
                            assertEquals(IsolationScope.SESSION, sandbox.getIsolationScope());
                            assertTrue(
                                    context.getBean(RuntimeStorageProperties.class).distributed());
                            var config = snapshot.toConfig();
                            assertTrue(
                                    config.getAccessKey().equals(artifact.getAccessKey()),
                                    "Existing BOS AK must be reused");
                            assertTrue(
                                    config.getSecretKey().equals(artifact.getSecretKey()),
                                    "Existing BOS SK must be reused");
                            assertTrue(
                                    config.getBucket().equals(artifact.getBucket()),
                                    "Existing BOS bucket must be reused");
                            assertEquals(
                                    artifact.getKeyPrefix() + "/sandbox-snapshots",
                                    config.getKeyPrefix());
                            System.out.println(
                                    "Workspace daily config: E2B enabled, SESSION isolation, BOS snapshots enabled; existing credentials reused");
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            SandboxSnapshotProperties.class,
            E2bSandboxProperties.class,
            ArtifactProperties.class,
            RuntimeStorageProperties.class
    })
    static class ConfigurationOnly {
    }
}
