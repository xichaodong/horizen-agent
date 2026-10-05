package dev.horizen.agent.adapter.agentscope.workspace.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

class ReadOnlyAgentsMdBaseStoreTest {
    @TempDir Path workspace;

    @Test
    void readsPublishedAgentsMdButRejectsAgentMutations() {
        InMemoryStore published = new InMemoryStore();
        List<String> namespace = List.of("agents", "horizen-web-agent", "users", "owner-a", "root");
        published.put(namespace, "AGENTS.md", Map.of("content", "published"));
        ReadOnlyAgentsMdBaseStore agentView = new ReadOnlyAgentsMdBaseStore(published);

        assertEquals("published", agentView.get(namespace, "AGENTS.md").value().get("content"));
        assertFalse(
                agentView.putIfVersion(namespace, "AGENTS.md", Map.of("content", "changed"), 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> agentView.put(namespace, "AGENTS.md", Map.of("content", "changed")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> agentView.delete(namespace, "AGENTS.md"));
        assertEquals("published", published.get(namespace, "AGENTS.md").value().get("content"));
    }

    @Test
    void keepsMemoryAndOtherWorkspacePathsWritable() {
        InMemoryStore delegate = new InMemoryStore();
        ReadOnlyAgentsMdBaseStore agentView = new ReadOnlyAgentsMdBaseStore(delegate);
        List<String> root = List.of("agents", "horizen-web-agent", "users", "owner-a", "root");
        List<String> memory = List.of("agents", "horizen-web-agent", "users", "owner-a", "memory");

        agentView.put(root, "MEMORY.md", Map.of("content", "memory"));
        agentView.put(memory, "2026-09-25.md", Map.of("content", "daily"));

        assertEquals(2, delegate.size());
    }

    @Test
    void agentScopeRemoteFilesystemIsolatesOwnersAndKeepsPublishedContentReadOnly() {
        InMemoryStore store = new InMemoryStore();
        var publisherFilesystem =
                new RemoteFilesystemSpec(store)
                        .isolationScope(IsolationScope.USER)
                        .toFilesystem(
                                workspace,
                                "horizen-web-agent",
                                IsolationScope.USER.toNamespaceFactory());
        var agentFilesystem =
                new RemoteFilesystemSpec(new ReadOnlyAgentsMdBaseStore(store))
                        .isolationScope(IsolationScope.USER)
                        .toFilesystem(
                                workspace,
                                "horizen-web-agent",
                                IsolationScope.USER.toNamespaceFactory());
        RuntimeContext ownerA =
                RuntimeContext.builder().userId("owner-a").sessionId("same-session").build();
        RuntimeContext ownerB =
                RuntimeContext.builder().userId("owner-b").sessionId("same-session").build();

        publisherFilesystem.write(ownerA, "AGENTS.md", "instruction-a");
        publisherFilesystem.write(ownerB, "AGENTS.md", "instruction-b");

        assertEquals(
                "instruction-a",
                agentFilesystem.read(ownerA, "AGENTS.md", 0, 0).fileData().content());
        assertEquals(
                "instruction-b",
                agentFilesystem.read(ownerB, "AGENTS.md", 0, 0).fileData().content());
        assertFalse(
                agentFilesystem
                        .edit(ownerA, "AGENTS.md", "instruction-a", "changed", false)
                        .isSuccess());
        assertEquals(
                "instruction-a",
                publisherFilesystem.read(ownerA, "AGENTS.md", 0, 0).fileData().content());

        WorkspaceContextMiddleware loader =
                new WorkspaceContextMiddleware(
                        new WorkspaceManager(workspace, agentFilesystem),
                        "horizen-web-agent",
                        null,
                        8_000,
                        true,
                        true);
        String ownerAPrompt =
                loader.onSystemPrompt(null, ownerA, "base-system").block(Duration.ofSeconds(2));
        assertTrue(ownerAPrompt.contains("instruction-a"));
        assertFalse(ownerAPrompt.contains("instruction-b"));
    }
}
