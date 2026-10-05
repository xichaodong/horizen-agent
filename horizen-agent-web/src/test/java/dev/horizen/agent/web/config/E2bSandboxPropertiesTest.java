package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.adapter.agentscope.workspace.filesystem.SessionWorkspaceIdentity;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClientOptions;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class E2bSandboxPropertiesTest {
    @Test
    void defaultsToDisabledUnconfiguredE2b() {
        E2bSandboxProperties properties =
                new E2bSandboxProperties(
                        false, null, null, null, null, null, null, null, null, null, null);

        assertFalse(properties.isEnabled());
        assertEquals(IsolationScope.SESSION, properties.getIsolationScope());
        assertEquals("", properties.getApiBaseUrl());
        assertEquals("", properties.getRuntimeBaseUrlPattern());
    }

    @Test
    void enabledSandboxRequiresApiKey() {
        assertThrows(IllegalArgumentException.class, () -> properties(true, ""));
    }

    @Test
    void ownerIsolationDoesNotCollideWhenSessionIdsMatch() {
        RuntimeContext first =
                RuntimeContext.builder().userId("owner-a").sessionId("same-session").build();
        RuntimeContext second =
                RuntimeContext.builder().userId("owner-b").sessionId("same-session").build();

        assertNotEquals(
                SessionWorkspaceIdentity.key(first, "agent"),
                SessionWorkspaceIdentity.key(second, "agent"));
    }

    @Test
    void buildsAgentScopeE2bSpecWithoutExposingCredential() {
        E2bSandboxProperties properties = properties(true, "private-api-key");
        var context = properties.toSpec().toSandboxContext(Path.of("workspace"));
        HttpE2bSandboxClientOptions options =
                (HttpE2bSandboxClientOptions) context.getClientOptions();

        assertEquals("https://api.example.test", options.getApiBaseUrl());
        assertEquals("https://49983-{sandbox_id}.example.test", options.getRuntimeBaseUrlPattern());
        assertEquals("test-template", options.getTemplateId());
        assertEquals("/tmp/horizen-agent", options.getWorkspaceRoot());
        assertEquals(524288, options.getMaxOutputBytes());
        assertEquals(IsolationScope.SESSION, context.getIsolationScope());
        assertTrue(context.getSnapshotSpec() instanceof NoopSnapshotSpec);
        assertFalse(properties.toString().contains("private-api-key"));
    }

    private static E2bSandboxProperties properties(boolean enabled, String apiKey) {
        return new E2bSandboxProperties(
                enabled,
                apiKey,
                "https://api.example.test",
                "https://49983-{sandbox_id}.example.test",
                "test-template",
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
