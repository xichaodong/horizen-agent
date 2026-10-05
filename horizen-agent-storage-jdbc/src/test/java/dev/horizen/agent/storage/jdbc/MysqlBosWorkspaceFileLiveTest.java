package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentAppend;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentCommit;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.storage.bos.BosArtifactContentStoreConfig;
import dev.horizen.agent.storage.bos.BosWorkspaceContentRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * 使用独立的本地 MySQL 临时库和已配置 BOS 前缀，清理本测试创建的全部行和对象。
 */
@EnabledIfSystemProperty(named = "horizen.workspace.bos.live", matches = "true")
class MysqlBosWorkspaceFileLiveTest {
    @Test
    void persistsOnlyMetadataAndStructuredAuditThenRestoresThroughAnotherRepository()
            throws Exception {
        Properties storage = load("../.env.yml");
        Properties artifact = load("../.env.yml");
        URI uri = URI.create(required(storage, "horizen.agent.storage.jdbc-url").substring(5));
        assertTrue(
                List.of("127.0.0.1", "localhost").contains(uri.getHost()),
                "only local MySQL is allowed");
        String base = "jdbc:mysql://" + uri.getRawAuthority() + "/";
        String suffix = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String user = required(storage, "horizen.agent.storage.jdbc-username"),
                password = storage.getProperty("horizen.agent.storage.jdbc-password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base + suffix, user, password));
        String database = "ha_workspace_bos_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        String prefix =
                required(artifact, "horizen.agent.artifact.bos.key-prefix")
                        + "/workspace-live/"
                        + UUID.randomUUID();
        var config =
                new BosArtifactContentStoreConfig(
                        required(artifact, "horizen.agent.artifact.bos.endpoint"),
                        required(artifact, "horizen.agent.artifact.bos.bucket"),
                        required(artifact, "horizen.agent.artifact.bos.access-key"),
                        required(artifact, "horizen.agent.artifact.bos.secret-key"),
                        prefix,
                        1024 * 1024);
        var source = new DriverManagerDataSource(base + database + suffix, user, password);
        var jdbc = new JdbcTemplate(source);
        List<String> references = new ArrayList<>();
        try (var firstContent = new BosWorkspaceContentRepository(config)) {
            new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                    .execute(source);
            var first = new JdbcWorkspaceDocumentRepository(source, firstContent);
            first.commit(
                    new WorkspaceDocumentCommit(
                            null,
                            List.of(
                                    new WorkspaceDocumentAppend(
                                            new WorkspaceDocumentKey(
                                                    "owner", "agent", "global", "MEMORY.md"),
                                            "operation",
                                            "- cloud-memory",
                                            "session",
                                            "turn",
                                            "tool-call"))));
            String reference =
                    jdbc.queryForObject("SELECT content_ref FROM ha_workspace_file", String.class);
            references.add(reference);
            assertFalse(reference.contains("cloud-memory"));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                                    + " table_name='ha_workspace_file' AND column_name='content'",
                            Integer.class));
            assertEquals(
                    "session",
                    jdbc.queryForObject(
                            "SELECT session_id FROM ha_workspace_operation", String.class));
            assertEquals(
                    "turn",
                    jdbc.queryForObject(
                            "SELECT turn_id FROM ha_workspace_operation", String.class));
            assertEquals(
                    "tool-call",
                    jdbc.queryForObject(
                            "SELECT tool_call_id FROM ha_workspace_operation", String.class));
        }
        try (var secondContent = new BosWorkspaceContentRepository(config)) {
            var second = new JdbcWorkspaceDocumentRepository(source, secondContent);
            assertEquals(
                    "- cloud-memory",
                    second.find(new WorkspaceDocumentKey("owner", "agent", "global", "MEMORY.md"))
                            .orElseThrow()
                            .getContent()
                            .strip());
            references.forEach(secondContent::delete);
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
        System.out.println(
                "Workspace BOS live: object body, SQL metadata/audit and cold repository restore passed;"
                        + " objects/schema cleaned");
    }

    private static Properties load(String path) throws Exception {
        var p = new Properties();
        try (var in = Files.newInputStream(Path.of(path))) {
            p.putAll(YamlConfigFiles.load(in));
        }
        return p;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing configuration: " + key);
        return value;
    }
}
