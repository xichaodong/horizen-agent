package dev.horizen.agent.adapter.agentscope.workspace.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

class DurableWorkspaceSandboxContextTest {
    @Test
    void databaseFailureCannotBeTreatedAsEmptyWorkspaceAndReleasesLease() {
        AtomicInteger closed = new AtomicInteger();
        var recovery =
                new DurableWorkspaceSandboxContext(
                        "agent",
                        defaults(),
                        repository(true),
                        key -> closed::incrementAndGet,
                        id -> {
                            fail("Must not create workspace on database outage");
                            return null;
                        });
        RuntimeContext context = context();
        assertThrows(IllegalStateException.class, () -> recovery.prepare(context));
        assertEquals(1, closed.get());
        DurableWorkspaceSandboxContext.release(context);
        assertEquals(1, closed.get());
    }

    @Test
    void permanentPointerSeedsAColdStateWhenAllRuntimeStateIsMissing() {
        AtomicInteger closed = new AtomicInteger();
        SandboxState fresh = new SandboxState() {};
        var recovery =
                new DurableWorkspaceSandboxContext(
                        "agent",
                        defaults(),
                        repository(false),
                        key -> closed::incrementAndGet,
                        id -> {
                            assertEquals("permanent-snapshot", id);
                            return fresh;
                        });
        RuntimeContext context = context();
        recovery.prepare(context);
        assertSame(fresh, context.get(SandboxContext.class).getExternalSandboxState());
        assertEquals(0, closed.get());
        DurableWorkspaceSandboxContext.release(context);
        assertEquals(1, closed.get());
    }

    private static WorkspaceSnapshotPointerRepository repository(boolean fail) {
        return new WorkspaceSnapshotPointerRepository() {
            public Optional<String> findSnapshotId(WorkspaceSnapshotKey key) {
                if (fail) throw new IllegalStateException("database unavailable");
                return Optional.of("permanent-snapshot");
            }

            public void saveCommitted(WorkspaceSnapshotKey key, String id) {}
        };
    }

    private static SandboxContext defaults() {
        return SandboxContext.builder()
                .isolationScope(IsolationScope.SESSION)
                .workspaceSpec(new WorkspaceSpec())
                .build();
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("owner").sessionId("session").build();
    }
}
