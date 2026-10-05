package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.tool.adapter.ToolInvocationScope;
import dev.horizen.agent.web.bootstrap.runtime.WorkspaceRuntimeConfigurer;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

import org.junit.jupiter.api.Test;

class SessionPlanScopeTest {
    @Test
    void plansSurviveANewFilesystemAndOnlyShareWithinTheOwningSession() {
        var store = new InMemoryStore();
        var a = WorkspaceRuntimeConfigurer.sessionPlansFilesystem(store);
        var owner = RuntimeContext.builder().userId("owner").sessionId("session").build();
        assertTrue(a.write(owner, "PLAN.md", "first plan").isSuccess());
        var b = WorkspaceRuntimeConfigurer.sessionPlansFilesystem(store);
        assertEquals("first plan", b.read(owner, "PLAN.md", 0, 20).fileData().content());
        assertFalse(
                b.exists(
                        RuntimeContext.builder().userId("owner").sessionId("other-session").build(),
                        "PLAN.md"));
        assertFalse(
                b.exists(
                        RuntimeContext.builder().userId("other-owner").sessionId("session").build(),
                        "PLAN.md"));
        var child =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("child-session")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner", "session", "turn"))
                        .build();
        assertEquals("first plan", b.read(child, "PLAN.md", 0, 20).fileData().content());
        assertTrue(b.edit(child, "PLAN.md", "first", "updated", false).isSuccess());
        assertEquals("updated plan", a.read(owner, "PLAN.md", 0, 20).fileData().content());
    }
}
