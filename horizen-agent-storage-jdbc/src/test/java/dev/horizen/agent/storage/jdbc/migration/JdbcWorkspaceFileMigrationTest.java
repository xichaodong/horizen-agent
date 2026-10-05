package dev.horizen.agent.storage.jdbc.migration;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

class JdbcWorkspaceFileMigrationTest {
    @Test
    void migratesMemoryBodyToObjectsAndDailyMarkdownToStructuredAuditRestartably() {
        var source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:workspace-migration-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(
                """
CREATE TABLE ha_workspace_document(owner_key VARCHAR(191),agent_key VARCHAR(128),scope_key VARCHAR(160),
    path_hash CHAR(64),document_path VARCHAR(512),content MEDIUMTEXT,size_bytes BIGINT,version BIGINT,
    created_at TIMESTAMP(6),updated_at TIMESTAMP(6),PRIMARY KEY(owner_key,agent_key,scope_key,path_hash))
""");
        jdbc.execute(
                """
CREATE TABLE ha_workspace_document_append(owner_key VARCHAR(191),agent_key VARCHAR(128),scope_key VARCHAR(160),
    path_hash CHAR(64),document_path VARCHAR(512),operation_id VARCHAR(191),applied_version BIGINT,
    created_at TIMESTAMP(6),PRIMARY KEY(owner_key,agent_key,scope_key,path_hash,operation_id))
""");
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        insert(jdbc, "MEMORY.md", "- remembered\n", 2, now);
        insert(jdbc, "memory/2026-10-03.md", "## Memory Save\n- remembered\n", 1, now);
        jdbc.update(
                "INSERT INTO ha_workspace_document_append VALUES (?,?,?,?,?,?,?,?)",
                "owner",
                "agent",
                "global",
                hash("MEMORY.md"),
                "MEMORY.md",
                "saved-once",
                2,
                Timestamp.from(now));
        var objects = new InMemoryWorkspaceContentRepository();
        var migration = new JdbcWorkspaceFileMigration(source, objects);
        assertEquals(2, migration.migrateBatch(10));
        assertEquals(0, migration.migrateBatch(10));
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_file", Integer.class));
        assertEquals(
                3,
                jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_operation", Integer.class));
        assertEquals(1, objects.objectCount());
        var repository = new JdbcWorkspaceDocumentRepository(source, objects);
        assertEquals(
                "- remembered\n",
                repository
                        .find(new WorkspaceDocumentKey("owner", "agent", "global", "MEMORY.md"))
                        .orElseThrow()
                        .getContent());
        assertTrue(
                repository
                        .find(
                                new WorkspaceDocumentKey(
                                        "owner", "agent", "global", "memory/2026-10-03.md"))
                        .isEmpty());
        assertEquals(
                "LEGACY_DAILY_IMPORT",
                jdbc.queryForObject(
                        "SELECT operation_type FROM ha_workspace_operation WHERE file_path LIKE 'memory/%' AND"
                                + " operation_type='LEGACY_DAILY_IMPORT'",
                        String.class));
        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_document", Integer.class));
    }

    private static void insert(
            JdbcTemplate jdbc, String path, String content, long version, Instant now) {
        jdbc.update(
                "INSERT INTO ha_workspace_document VALUES (?,?,?,?,?,?,?,?,?,?)",
                "owner",
                "agent",
                "global",
                hash(path),
                path,
                content,
                (long) content.getBytes(StandardCharsets.UTF_8).length,
                version,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private static String hash(String path) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
