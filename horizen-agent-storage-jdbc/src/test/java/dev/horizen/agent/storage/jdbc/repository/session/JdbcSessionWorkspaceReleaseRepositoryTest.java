package dev.horizen.agent.storage.jdbc.repository.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.identity.ExecutionIdentity;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

class JdbcSessionWorkspaceReleaseRepositoryTest {
    private JdbcDataSource source;
    private JdbcSessionTurnStore sessions;
    private JdbcSessionWorkspaceReleaseRepository versions;
    private final AgentCatalogKey catalog = new AgentCatalogKey(7, "test-agent");

    @BeforeEach
    void prepare() {
        source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:session-release-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        sessions = new JdbcSessionTurnStore(source);
        versions = new JdbcSessionWorkspaceReleaseRepository(source);
        start("owner");
    }

    @Test
    void concurrentInstancesBindExactlyOneReleaseAndRestartReadsTheSameReference()
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> versions.bindIfAbsent("owner", "session", release(1)));
            var b =
                    pool.submit(
                            () ->
                                    new JdbcSessionWorkspaceReleaseRepository(source)
                                            .bindIfAbsent("owner", "session", release(2)));
            assertEquals(a.get(), b.get());
            assertEquals(
                    a.get(),
                    new JdbcSessionWorkspaceReleaseRepository(source)
                            .find("owner", "session")
                            .orElseThrow());
            assertEquals(
                    a.get(),
                    sessions.findSession("owner", "session").orElseThrow().getWorkspaceRelease());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ownerAndCatalogBoundariesCannotBeChanged() {
        versions.bindIfAbsent("owner", "session", release(1));
        start("other");
        versions.bindIfAbsent("other", "session", release(2));
        assertEquals(1, versions.find("owner", "session").orElseThrow().getReleaseId());
        assertEquals(2, versions.find("other", "session").orElseThrow().getReleaseId());
        assertThrows(
                SecurityException.class,
                () ->
                        versions.bindIfAbsent(
                                "owner",
                                "session",
                                new SessionWorkspaceRelease(
                                        new AgentCatalogKey(8, "test-agent"), 2, "b".repeat(64))));
        assertThrows(
                SecurityException.class,
                () ->
                        versions.bindIfAbsent(
                                "owner",
                                "session",
                                new SessionWorkspaceRelease(
                                        new AgentCatalogKey(7, "different-agent"),
                                        2,
                                        "b".repeat(64))));
        assertTrue(versions.find("missing", "session").isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> versions.bindIfAbsent("missing", "session", release(1)));
    }

    private SessionWorkspaceRelease release(long id) {
        return new SessionWorkspaceRelease(catalog, id, (id == 1 ? "a" : "b").repeat(64));
    }

    private void start(String owner) {
        Instant now = Instant.now();
        sessions.startTurn(
                new StartTurnCommand(
                        new ExecutionIdentity(owner, "actor"),
                        "session",
                        owner + "-turn",
                        "request",
                        "instance",
                        "user-" + owner,
                        "hello",
                        now,
                        now.plusSeconds(60),
                        now.plusSeconds(30)));
    }
}
