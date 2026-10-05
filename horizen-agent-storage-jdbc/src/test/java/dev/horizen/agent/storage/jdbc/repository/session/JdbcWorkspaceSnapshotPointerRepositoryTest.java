package dev.horizen.agent.storage.jdbc.repository.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.identity.ExecutionIdentity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.UUID;

class JdbcWorkspaceSnapshotPointerRepositoryTest {
    @Test
    void sessionPointersSurviveNewRepositoryInstanceAndAreOwnerScoped() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:pointers_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        var sessions = new JdbcSessionTurnStore(source);
        createSession(sessions, "owner-a", "session-a");
        createSession(sessions, "owner-a", "session-b");
        createSession(sessions, "owner-b", "session-a");
        var first = new JdbcWorkspaceSnapshotPointerRepository(source);
        var key = new WorkspaceSnapshotKey("owner-a", "session-a");
        assertTrue(first.findSnapshotId(key).isEmpty());
        first.saveCommitted(key, "snapshot-one");
        var second = new JdbcWorkspaceSnapshotPointerRepository(source);
        assertEquals("snapshot-one", second.findSnapshotId(key).orElseThrow());
        second.saveCommitted(key, "snapshot-two");
        assertEquals("snapshot-two", first.findSnapshotId(key).orElseThrow());
        assertTrue(
                first.findSnapshotId(new WorkspaceSnapshotKey("owner-b", "session-a")).isEmpty());
        assertTrue(
                first.findSnapshotId(new WorkspaceSnapshotKey("owner-a", "session-b")).isEmpty());
        assertEquals(
                "snapshot-two",
                sessions.findSession("owner-a", "session-a").orElseThrow().getSnapshotId());
        second.saveCommitted(key, "snapshot-two");
        assertThrows(
                IllegalStateException.class,
                () ->
                        second.saveCommitted(
                                new WorkspaceSnapshotKey("owner-a", "missing-session"),
                                "snapshot-x"));
        assertThrows(IllegalArgumentException.class, () -> second.saveCommitted(key, "../invalid"));
        assertEquals("snapshot-two", first.findSnapshotId(key).orElseThrow());
    }

    private static void createSession(JdbcSessionTurnStore sessions, String owner, String session) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        sessions.startTurn(
                new StartTurnCommand(
                        new ExecutionIdentity(owner, owner),
                        session,
                        "turn-" + id,
                        "request-" + id,
                        "executor",
                        "message-" + id,
                        "fixture",
                        now,
                        now.plusSeconds(300),
                        now.plusSeconds(30)));
    }
}
