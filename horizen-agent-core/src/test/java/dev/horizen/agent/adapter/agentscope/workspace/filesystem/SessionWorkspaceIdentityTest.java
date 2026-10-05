package dev.horizen.agent.adapter.agentscope.workspace.filesystem;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.adapter.agentscope.workspace.snapshot.NonCachedSandboxStateStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;

import lombok.Data;
import lombok.NoArgsConstructor;

import org.junit.jupiter.api.Test;

class SessionWorkspaceIdentityTest {
    @Test
    void executionLocksIncludeOwnerAgentAndSession() {
        var first = context("owner-a", "session-a");
        var key = SessionWorkspaceIdentity.key(first, "agent-a");
        assertEquals(key, SessionWorkspaceIdentity.key(context("owner-a", "session-a"), "agent-a"));
        assertNotEquals(
                key, SessionWorkspaceIdentity.key(context("owner-a", "session-b"), "agent-a"));
        assertNotEquals(
                key, SessionWorkspaceIdentity.key(context("owner-b", "session-a"), "agent-a"));
        assertNotEquals(key, SessionWorkspaceIdentity.key(first, "agent-b"));
    }

    @Test
    void runtimeConversationsRemainCachedWhileOwnerLessSandboxRecordsAreIgnored() {
        var underlying = new InMemoryAgentStateStore();
        var store = new NonCachedSandboxStateStore(underlying);
        var value = new FixtureState();
        store.save("owner", "session", "fixture", value);
        assertTrue(store.get("owner", "session", "fixture", FixtureState.class).isPresent());
        store.save(null, "sandbox/session/same-id", "_sandbox_state", value);
        assertTrue(
                underlying
                        .get(null, "sandbox/session/same-id", "_sandbox_state", FixtureState.class)
                        .isEmpty());
        underlying.save(null, "sandbox/session/same-id", "_sandbox_state", value);
        assertTrue(
                store.get(null, "sandbox/session/same-id", "_sandbox_state", FixtureState.class)
                        .isEmpty());
    }

    private static RuntimeContext context(String owner, String session) {
        return RuntimeContext.builder().userId(owner).sessionId(session).build();
    }

    @Data
    @NoArgsConstructor
    static class FixtureState implements State {
        private String text = "fixture";
    }
}
