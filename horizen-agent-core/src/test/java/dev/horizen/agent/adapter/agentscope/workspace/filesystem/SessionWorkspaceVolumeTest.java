package dev.horizen.agent.adapter.agentscope.workspace.filesystem;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.adapter.agentscope.workspace.snapshot.SandboxSnapshotCheckpoint;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;

class SessionWorkspaceVolumeTest {
    @Test
    void planAndTaskFilesRestoreAcrossExecutionViewsAndOwnersStayIsolated() {
        var archives = new Archives();
        var pointers = new Pointers();
        RuntimeContext first = context("owner", "session", "turn-1");
        SessionWorkspaceVolume.prepare(
                first, "agent", archives, pointers, SandboxExecutionGuard.noop(), 1024 * 1024, 100);
        var volume = first.get(SessionWorkspaceVolume.class);
        assertTrue(volume.filesystem().write(first, "plans/PLAN.md", "step one").isSuccess());
        volume.changed();
        volume.checkpoint();
        String snapshot =
                pointers.findSnapshotId(new WorkspaceSnapshotKey("owner", "session")).orElseThrow();
        SessionWorkspaceVolume.release(first);

        RuntimeContext second = context("owner", "session", "turn-2");
        SessionWorkspaceVolume.prepare(
                second,
                "agent",
                archives,
                pointers,
                SandboxExecutionGuard.noop(),
                1024 * 1024,
                100);
        assertEquals(
                "step one",
                second.get(SessionWorkspaceVolume.class)
                        .filesystem()
                        .read(second, "plans/PLAN.md", 0, 20)
                        .fileData()
                        .content());
        SessionWorkspaceVolume.release(second);
        assertTrue(archives.exists(snapshot));

        RuntimeContext other = context("other", "session", "turn-3");
        SessionWorkspaceVolume.prepare(
                other, "agent", archives, pointers, SandboxExecutionGuard.noop(), 1024 * 1024, 100);
        assertFalse(
                other.get(SessionWorkspaceVolume.class)
                        .filesystem()
                        .exists(other, "plans/PLAN.md"));
        SessionWorkspaceVolume.release(other);
    }

    @Test
    void staleExecutionCannotOverwriteANewerCommittedDirectory() {
        var archives = new Archives();
        var pointers = new Pointers();
        RuntimeContext left = context("owner", "session", "left");
        RuntimeContext right = context("owner", "session", "right");
        SessionWorkspaceVolume.prepare(
                left, "agent", archives, pointers, SandboxExecutionGuard.noop(), 1024 * 1024, 100);
        SessionWorkspaceVolume.prepare(
                right, "agent", archives, pointers, SandboxExecutionGuard.noop(), 1024 * 1024, 100);
        left.get(SessionWorkspaceVolume.class).filesystem().write(left, "left.txt", "left");
        left.get(SessionWorkspaceVolume.class).changed();
        right.get(SessionWorkspaceVolume.class).filesystem().write(right, "right.txt", "right");
        right.get(SessionWorkspaceVolume.class).changed();
        left.get(SessionWorkspaceVolume.class).checkpoint();
        assertThrows(
                SandboxSnapshotCheckpoint.SnapshotCheckpointException.class,
                () -> right.get(SessionWorkspaceVolume.class).checkpoint());
        SessionWorkspaceVolume.release(left);
        SessionWorkspaceVolume.release(right);
        RuntimeContext restored = context("owner", "session", "next");
        SessionWorkspaceVolume.prepare(
                restored,
                "agent",
                archives,
                pointers,
                SandboxExecutionGuard.noop(),
                1024 * 1024,
                100);
        assertTrue(
                restored.get(SessionWorkspaceVolume.class)
                        .filesystem()
                        .exists(restored, "left.txt"));
        assertFalse(
                restored.get(SessionWorkspaceVolume.class)
                        .filesystem()
                        .exists(restored, "right.txt"));
        SessionWorkspaceVolume.release(restored);
    }

    private static RuntimeContext context(String owner, String session, String turn) {
        return RuntimeContext.builder()
                .userId(owner)
                .sessionId(session)
                .put(ArtifactExecutionContext.class, new ArtifactExecutionContext(turn))
                .build();
    }

    private static final class Archives implements WorkspaceSnapshotRepository {
        private final Map<String, byte[]> values = new HashMap<>();

        public void upload(String id, InputStream input) throws IOException {
            values.put(id, input.readAllBytes());
        }

        public InputStream download(String id) throws IOException {
            byte[] value = values.get(id);
            if (value == null) throw new FileNotFoundException(id);
            return new ByteArrayInputStream(value);
        }

        public boolean exists(String id) {
            return values.containsKey(id);
        }
    }

    private static final class Pointers implements WorkspaceSnapshotPointerRepository {
        private final Map<WorkspaceSnapshotKey, String> values = new HashMap<>();

        public synchronized Optional<String> findSnapshotId(WorkspaceSnapshotKey key) {
            return Optional.ofNullable(values.get(key));
        }

        public synchronized void saveCommitted(WorkspaceSnapshotKey key, String id) {
            values.put(key, id);
        }

        public synchronized boolean compareAndSetCommitted(
                WorkspaceSnapshotKey key, String expected, String next, String turn) {
            if (!Objects.equals(values.get(key), expected)) return false;
            values.put(key, next);
            return true;
        }
    }
}
