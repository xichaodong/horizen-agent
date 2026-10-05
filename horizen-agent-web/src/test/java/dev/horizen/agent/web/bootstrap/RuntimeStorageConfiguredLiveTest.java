package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.web.LiveConfiguration;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.RuntimeStorageProperties;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * 显式启用的本地分布式运行时只读验证，不从源码加载凭据，也不创建记录。
 */
@EnabledIfSystemProperty(named = "horizen.storage.live", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuntimeStorageConfiguredLiveTest {
    @Test
    @Order(2)
    void configuredDatabaseHasCurrentRuntimeSchema() throws Exception {
        Properties config = new Properties();
        Path file = Path.of("..", ".env.yml").normalize();
        try (InputStream input = Files.newInputStream(file)) {
            config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        RuntimeStorageProperties properties =
                new RuntimeStorageProperties(
                        RuntimeStorageProperties.Mode.DISTRIBUTED,
                        required(config, "horizen.agent.storage.jdbc-url"),
                        required(config, "horizen.agent.storage.jdbc-username"),
                        required(config, "horizen.agent.storage.jdbc-password"),
                        Integer.parseInt(
                                config.getProperty(
                                        "horizen.agent.storage.jdbc-maximum-pool-size", "2")),
                        Integer.parseInt(
                                config.getProperty(
                                        "horizen.agent.storage.redis-maximum-pool-size", "8")),
                        required(config, "horizen.agent.storage.redis-url"),
                        config.getProperty(
                                "horizen.agent.storage.redis-key-prefix", "horizen-agent:"),
                        "schema-live-check",
                        Duration.ofMinutes(1),
                        Duration.ofHours(24),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(1));
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        properties.getJdbcUrl(),
                        properties.getJdbcUsername(),
                        properties.getJdbcPassword());
        assertDoesNotThrow(
                () ->
                        new JdbcTemplate(dataSource)
                                .query(
                                        "SELECT request_json, response_json, version FROM ha_interaction WHERE 1 = 0",
                                        ignored -> {}));
        assertDoesNotThrow(
                () ->
                        new JdbcTemplate(dataSource)
                                .query(
                                        "SELECT project_id, agent_key, workspace_release_id, workspace_release_hash"
                                                + " FROM ha_session WHERE 1 = 0",
                                        ignored -> {}));
        assertDoesNotThrow(
                () ->
                        new JdbcTemplate(dataSource)
                                .query(
                                        "SELECT content_ref, version FROM ha_workspace_file WHERE 1 = 0",
                                        ignored -> {}));
        assertDoesNotThrow(
                () ->
                        new JdbcTemplate(dataSource)
                                .query(
                                        "SELECT snapshot_id FROM ha_session WHERE 1 = 0",
                                        ignored -> {}));
        try (RuntimeStorage storage =
                RuntimeStorage.open(
                        properties,
                        InMemoryWorkspaceContentRepository.shared("runtime-storage-tests"))) {
            RuntimeStorage.DatabasePoolStatus pool = storage.databasePoolStatus();
            assertEquals(properties.getJdbcMaximumPoolSize(), pool.getMaximumPoolSize());
            assertTrue(pool.getTotalConnections() >= 1);
            RuntimeStorage.RedisPoolsStatus redisPools = storage.redisPoolsStatus();
            assertEquals(
                    properties.getRedisMaximumPoolSize(),
                    redisPools.getCommands().getMaximumPoolSize());
            assertEquals(
                    properties.getRedisMaximumPoolSize(),
                    redisPools.getSubscriptions().getMaximumPoolSize());
            assertDoesNotThrow(
                    () -> storage.getArtifacts().find("schema-check", "art_schema_check"));
            assertDoesNotThrow(
                    () ->
                            storage.getAskUsers()
                                    .findPending(
                                            "schema-check",
                                            "session_schema_check",
                                            "turn_schema_check"));
            assertDoesNotThrow(
                    () ->
                            storage.getPresentations()
                                    .listForSession("schema-check", "session_schema_check"));
            assertDoesNotThrow(() -> storage.getSessionTurns().listSessions("schema-check", 1, 0));
            assertDoesNotThrow(
                    () ->
                            storage.getTimeline()
                                    .listForSession("schema-check", "session_schema_check"));
            assertDoesNotThrow(
                    () ->
                            storage.getWorkspaceReleases()
                                    .find("schema-check", "session_schema_check"));
            assertDoesNotThrow(
                    () ->
                            storage.getWorkspaceDocuments()
                                    .list(
                                            "schema-check",
                                            "schema-check-agent",
                                            "global",
                                            "",
                                            1,
                                            0));
        }
    }

    @Test
    @Order(1)
    @EnabledIfSystemProperty(
            named = "horizen.storage.apply-approval-presentation",
            matches = "true")
    void appliesApprovalPresentationMigration() throws Exception {
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("..", ".env.yml").normalize())) {
            config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        Path schema =
                Path.of(
                        "..",
                        "horizen-agent-storage-jdbc",
                        "src",
                        "main",
                        "resources",
                        "schema",
                        "mysql-approval-presentation.sql");
        new ResourceDatabasePopulator(new FileSystemResource(schema))
                .execute(
                        new DriverManagerDataSource(
                                required(config, "horizen.agent.storage.jdbc-url"),
                                required(config, "horizen.agent.storage.jdbc-username"),
                                required(config, "horizen.agent.storage.jdbc-password")));
    }

    @Test
    @Order(1)
    @EnabledIfSystemProperty(
            named = "horizen.storage.apply-session-workspace-release",
            matches = "true")
    void appliesSessionWorkspaceReleaseColumns() throws Exception {
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("..", ".env.yml").normalize())) {
            config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        Path schema =
                Path.of(
                        "..",
                        "horizen-agent-storage-jdbc",
                        "src",
                        "main",
                        "resources",
                        "schema",
                        "mysql-session-workspace-release.sql");
        new ResourceDatabasePopulator(new FileSystemResource(schema))
                .execute(
                        new DriverManagerDataSource(
                                required(config, "horizen.agent.storage.jdbc-url"),
                                required(config, "horizen.agent.storage.jdbc-username"),
                                required(config, "horizen.agent.storage.jdbc-password")));
    }

    @Test
    @Order(1)
    @EnabledIfSystemProperty(named = "horizen.storage.apply-workspace-document", matches = "true")
    void appliesWorkspaceDocumentMigration() throws Exception {
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("..", ".env.yml").normalize())) {
            config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        Path schema =
                Path.of(
                        "..",
                        "horizen-agent-storage-jdbc",
                        "src",
                        "main",
                        "resources",
                        "schema",
                        "mysql-workspace-document.sql");
        new ResourceDatabasePopulator(new FileSystemResource(schema))
                .execute(
                        new DriverManagerDataSource(
                                required(config, "horizen.agent.storage.jdbc-url"),
                                required(config, "horizen.agent.storage.jdbc-username"),
                                required(config, "horizen.agent.storage.jdbc-password")));
    }

    @Test
    @Order(1)
    @EnabledIfSystemProperty(named = "horizen.storage.apply-schema", matches = "true")
    void appliesRuntimeMigrations() throws Exception {
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("..", ".env.yml").normalize())) {
            config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        Path schema =
                Path.of("..", "horizen-agent-storage-jdbc", "src", "main", "resources", "schema");
        new ResourceDatabasePopulator(
                        new FileSystemResource(schema.resolve("mysql-artifact-ask-user.sql")),
                        new FileSystemResource(schema.resolve("mysql-presentation.sql")),
                        new FileSystemResource(schema.resolve("mysql-session-catalog.sql")),
                        new FileSystemResource(schema.resolve("mysql-turn-timeline.sql")),
                        new FileSystemResource(
                                schema.resolve("mysql-session-workspace-release.sql")),
                        new FileSystemResource(schema.resolve("mysql-workspace-document.sql")),
                        new FileSystemResource(
                                schema.resolve("mysql-session-workspace-snapshot.sql")))
                .execute(
                        new DriverManagerDataSource(
                                required(config, "horizen.agent.storage.jdbc-url"),
                                required(config, "horizen.agent.storage.jdbc-username"),
                                required(config, "horizen.agent.storage.jdbc-password")));
    }

    @Test
    @Order(1)
    @EnabledIfSystemProperty(named = "horizen.storage.apply-session-catalog", matches = "true")
    void appliesSessionCatalogMigration() throws Exception {
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("..", ".env.yml").normalize())) {
            config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        Path schema =
                Path.of(
                        "..",
                        "horizen-agent-storage-jdbc",
                        "src",
                        "main",
                        "resources",
                        "schema",
                        "mysql-session-catalog.sql");
        new ResourceDatabasePopulator(new FileSystemResource(schema))
                .execute(
                        new DriverManagerDataSource(
                                required(config, "horizen.agent.storage.jdbc-url"),
                                required(config, "horizen.agent.storage.jdbc-username"),
                                required(config, "horizen.agent.storage.jdbc-password")));
    }

    @Test
    @Order(1)
    @EnabledIfSystemProperty(named = "horizen.storage.apply-turn-timeline", matches = "true")
    void appliesTurnTimelineMigration() throws Exception {
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("..", ".env.yml").normalize())) {
            config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        Path schema =
                Path.of(
                        "..",
                        "horizen-agent-storage-jdbc",
                        "src",
                        "main",
                        "resources",
                        "schema",
                        "mysql-turn-timeline.sql");
        new ResourceDatabasePopulator(new FileSystemResource(schema))
                .execute(
                        new DriverManagerDataSource(
                                required(config, "horizen.agent.storage.jdbc-url"),
                                required(config, "horizen.agent.storage.jdbc-username"),
                                required(config, "horizen.agent.storage.jdbc-password")));
    }

    private static String required(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing " + key);
        return value.trim();
    }
}
