package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.web.LiveConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** 默认只检查；增量 DDL 需要单独显式启用 apply-workspace 标志。 */
@EnabledIfSystemProperty(named = "horizen.workspace.schema.live", matches = "true")
class WorkspaceSchemaConfiguredLiveTest {
    @Test
    void inspectsOrAddsTheRequiredWorkspaceSchema() throws Exception {
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("..", ".env.yml"))) {
            config.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        var source =
                new DriverManagerDataSource(
                        required(config, "horizen.agent.storage.jdbc-url"),
                        required(config, "horizen.agent.storage.jdbc-username"),
                        required(config, "horizen.agent.storage.jdbc-password"));
        var jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            System.out.println(
                    "Workspace schema: MySQL version="
                            + connection.getMetaData().getDatabaseProductVersion());
        }
        boolean sessionColumn = hasColumn(jdbc, "ha_session", "snapshot_id");
        System.out.println("Workspace schema: ha_session.snapshot_id=" + sessionColumn);
        for (String table : List.of("ha_workspace_operation", "ha_workspace_file")) {
            System.out.println("Workspace schema: " + table + "=" + hasTable(jdbc, table));
        }
        if (!Boolean.getBoolean("horizen.storage.apply-workspace")) return;
        if (!sessionColumn) {
            new ResourceDatabasePopulator(
                            new ClassPathResource("schema/mysql-session-workspace-snapshot.sql"))
                    .execute(source);
        }
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql-workspace-file.sql"))
                .execute(source);
        assertTrue(hasColumn(jdbc, "ha_session", "snapshot_id"));
        assertTrue(hasColumn(jdbc, "ha_workspace_file", "content_ref"));
        assertTrue(hasColumn(jdbc, "ha_workspace_file", "workspace_area"));
        assertTrue(hasColumn(jdbc, "ha_workspace_file", "file_kind"));
        assertTrue(hasColumn(jdbc, "ha_workspace_file", "write_policy"));
        assertTrue(hasColumn(jdbc, "ha_workspace_operation", "change_json"));
        System.out.println(
                "Workspace schema: cloud-file DDL applied; inline rows require the explicit migration runner");
    }

    private static boolean hasTable(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                        Integer.class,
                        table)
                > 0;
    }

    private static boolean hasColumn(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                        Integer.class,
                        table,
                        column)
                > 0;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing configuration: " + key);
        return value;
    }
}
