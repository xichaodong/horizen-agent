package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactSource;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcArtifactStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcTurnTimelineStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

@EnabledIfSystemProperty(named = "horizen.mysql.artifact.history.live", matches = "true")
class MysqlArtifactHistoryUpgradeLiveTest {
    @Test
    void migratesReferencesAndAttachmentMetadataThenAcceptsConcurrentUses() throws Exception {
        Properties config = new Properties();
        try (var input =
                Files.newBufferedReader(
                        Path.of(
                                System.getProperty(
                                        "horizen.mysql.artifact.history.config", "../.env.yml")))) {
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
        String database =
                "ha_artifact_history_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        try {
            var source =
                    new DriverManagerDataSource(prefix + database + suffix, username, password);
            var jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(
                            new ClassPathResource("schema/mysql.sql"),
                            new ClassPathResource("schema/legacy-artifact-reference.sql"))
                    .execute(source);
            Instant at = Instant.now();
            var stamp = Timestamp.from(at);
            var artifacts = new JdbcArtifactStore(source);
            var sessions = new JdbcSessionTurnStore(source);
            for (String id : List.of("file-a", "file-b"))
                artifacts.create(
                        new Artifact(
                                id,
                                "owner",
                                ArtifactKind.FILE,
                                ArtifactState.READY,
                                id,
                                "text/plain",
                                "objects/" + id,
                                8L,
                                "a".repeat(64),
                                null,
                                ArtifactSource.USER,
                                null,
                                null,
                                at,
                                at,
                                null,
                                0));
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
            jdbc.update(
                    "INSERT INTO ha_artifact_reference VALUES (?,?,?,?,?,?,?,?)",
                    "owner",
                    "old-input",
                    "file-a",
                    "session",
                    "turn",
                    "INPUT",
                    null,
                    stamp);
            jdbc.update(
                    "INSERT INTO ha_artifact_reference VALUES (?,?,?,?,?,?,?,?)",
                    "owner",
                    "old-output",
                    "file-a",
                    "session",
                    "turn",
                    "OUTPUT",
                    "call",
                    stamp);
            jdbc.update(
                    "INSERT INTO ha_artifact_reference VALUES (?,?,?,?,?,?,?,?)",
                    "owner",
                    "old-working",
                    "file-b",
                    "session",
                    "turn",
                    "WORKING",
                    null,
                    stamp);
            jdbc.execute(
                    "ALTER TABLE ha_conversation_history DROP INDEX idx_ha_history_artifact, DROP INDEX"
                            + " idx_ha_history_artifact_session, DROP COLUMN artifact_id, DROP COLUMN"
                            + " artifact_role");
            new ResourceDatabasePopulator(
                            new ClassPathResource("schema/mysql-artifact-history-columns.sql"),
                            new ClassPathResource("schema/mysql-artifact-history-upgrade.sql"))
                    .execute(source);
            assertEquals(3, artifacts.listReferencesForSession("owner", "session").size());
            assertEquals(
                    List.of("file-a"),
                    sessions.listFinalMessages("owner", "session").get(0).getArtifactIds());
            assertEquals(1, artifacts.listRecentOutputs("owner", "session", 10).size());
            assertEquals(
                    3,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ha_artifact_reference", Integer.class));
            assertTrue(artifacts.listReferences("other", "file-a").isEmpty());
            var pool = Executors.newFixedThreadPool(2);
            try {
                var a =
                        pool.submit(
                                () ->
                                        artifacts.addReference(
                                                new ArtifactReference(
                                                        "new-input-a",
                                                        "owner",
                                                        "file-a",
                                                        "session",
                                                        "turn",
                                                        ArtifactReferenceRole.INPUT,
                                                        null,
                                                        at)));
                var b =
                        pool.submit(
                                () ->
                                        new JdbcArtifactStore(source)
                                                .addReference(
                                                        new ArtifactReference(
                                                                "new-input-b",
                                                                "owner",
                                                                "file-b",
                                                                "session",
                                                                "turn",
                                                                ArtifactReferenceRole.INPUT,
                                                                null,
                                                                at)));
                a.get();
                b.get();
            } finally {
                pool.shutdownNow();
            }
            assertEquals(
                    Set.of("file-a", "file-b"),
                    new HashSet<>(
                            sessions.listFinalMessages("owner", "session")
                                    .get(0)
                                    .getArtifactIds()));
            assertEquals(1, artifacts.listForSession("owner", "session", 1).size());
            assertTrue(
                    new JdbcTurnTimelineStore(source).listForSession("owner", "session").isEmpty());
            System.out.println(
                    "MySQL Artifact history: upgrade kept source references, restored attachments, concurrent"
                            + " metadata updates and bounded distinct queries passed");
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }
}
