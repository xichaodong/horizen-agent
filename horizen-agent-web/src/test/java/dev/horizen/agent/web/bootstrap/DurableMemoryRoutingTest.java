package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.adapter.agentscope.workspace.document.ReadOnlyAgentsMdBaseStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class DurableMemoryRoutingTest {
    @TempDir Path workspace;

    @Test
    void sharesMemoryAcrossSessionsButIsolatesOwners() {
        AbstractFilesystem memory =
                new RemoteFilesystemSpec(new ReadOnlyAgentsMdBaseStore(new InMemoryStore()))
                        .isolationScope(IsolationScope.USER)
                        .toFilesystem(
                                workspace,
                                "horizen-web-agent",
                                IsolationScope.USER.toNamespaceFactory());
        RuntimeContext first = context("owner-a", "session-1");
        RuntimeContext second = context("owner-a", "session-2");
        RuntimeContext other = context("owner-b", "session-1");

        assertTrue(memory.write(first, "MEMORY.md", "- prefers concise answers\n").isSuccess());
        assertTrue(memory.write(first, "memory/2026-09-27.md", "- selected Java 17\n").isSuccess());
        assertTrue(
                memory.read(second, "MEMORY.md", 0, 50)
                        .fileData()
                        .content()
                        .contains("prefers concise answers"));
        assertTrue(
                memory.read(second, "memory/2026-09-27.md", 0, 50)
                        .fileData()
                        .content()
                        .contains("selected Java 17"));
        assertFalse(memory.exists(other, "MEMORY.md"));
        assertFalse(memory.exists(other, "memory/2026-09-27.md"));
    }

    private static RuntimeContext context(String owner, String session) {
        return RuntimeContext.builder().userId(owner).sessionId(session).build();
    }
}
