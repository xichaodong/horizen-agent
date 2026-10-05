package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionWorkspaceReleaseRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

@EnabledIfSystemProperty(named = "horizen.mysql.workspace.live", matches = "true")
class MysqlSessionWorkspaceReleaseLiveTest {
    @Test
    void upgradesSessionColumnsAndAtomicallyBindsOneCompletePublication() throws Exception {
        Properties config = new Properties();
        try (var input =
                Files.newBufferedReader(
                        Path.of(
                                System.getProperty(
                                        "horizen.mysql.workspace.config", "../.env.yml")))) {
            config.putAll(YamlConfigFiles.load(input));
        }
        URI uri = URI.create(config.getProperty("horizen.agent.storage.jdbc-url").substring(5));
        assertTrue(
                List.of("127.0.0.1", "localhost").contains(uri.getHost()),
                "only local MySQL is allowed");
        String prefix = "jdbc:mysql://" + uri.getRawAuthority() + "/";
        String suffix = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String username = config.getProperty("horizen.agent.storage.jdbc-username");
        String password = config.getProperty("horizen.agent.storage.jdbc-password", "");
        var admin =
                new JdbcTemplate(new DriverManagerDataSource(prefix + suffix, username, password));
        String database = "ha_workspace_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        try {
            var source =
                    new DriverManagerDataSource(prefix + database + suffix, username, password);
            var jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                    .execute(source);
            Instant at = Instant.now();
            var sessions = new JdbcSessionTurnStore(source);
            sessions.startTurn(
                    new StartTurnCommand(
                            new ExecutionIdentity("owner", "actor"),
                            "session",
                            "turn",
                            "request",
                            "instance",
                            "message",
                            "hello",
                            at,
                            at.plusSeconds(60),
                            at.plusSeconds(30)));
            jdbc.execute(
                    "ALTER TABLE ha_session DROP COLUMN project_id, DROP COLUMN agent_key, DROP COLUMN"
                            + " workspace_release_id, DROP COLUMN workspace_release_hash");
            new ResourceDatabasePopulator(
                            new ClassPathResource("schema/mysql-session-workspace-release.sql"))
                    .execute(source);
            var versions = new JdbcSessionWorkspaceReleaseRepository(source);
            assertTrue(versions.find("owner", "session").isEmpty());
            var r1 =
                    new SessionWorkspaceRelease(
                            new AgentCatalogKey(7, "test-agent"), 1, "a".repeat(64));
            var r2 =
                    new SessionWorkspaceRelease(
                            new AgentCatalogKey(7, "test-agent"), 2, "b".repeat(64));
            var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(() -> versions.bindIfAbsent("owner", "session", r1));
                var b =
                        pool.submit(
                                () ->
                                        new JdbcSessionWorkspaceReleaseRepository(source)
                                                .bindIfAbsent("owner", "session", r2));
                assertEquals(a.get(), b.get());
                assertEquals(
                        a.get(),
                        sessions.findSession("owner", "session")
                                .orElseThrow()
                                .getWorkspaceRelease());
            } finally {
                pool.shutdownNow();
            }
            assertTrue(versions.find("other", "session").isEmpty());
            assertThrows(
                    SecurityException.class,
                    () ->
                            versions.bindIfAbsent(
                                    "owner",
                                    "session",
                                    new SessionWorkspaceRelease(
                                            new AgentCatalogKey(8, "test-agent"),
                                            1,
                                            "a".repeat(64))));
            assertEquals(
                    9,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                                    + " table_type='BASE TABLE'",
                            Integer.class));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                                    + " table_name='ha_skill_session_binding'",
                            Integer.class));
            System.out.println(
                    "MySQL Session workspace: column upgrade, concurrent binding, catalog/owner isolation and"
                            + " seven-table schema passed");
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }
}
