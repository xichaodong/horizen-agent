package dev.horizen.agent.storage.jdbc.repository.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.execution.session.SessionHistoryQuery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * 在可丢弃的数据库中，通过 MySQL 原生 JSON 函数验证同一召回契约。
 */
@EnabledIfSystemProperty(named = "horizen.mysql.recall.live", matches = "true")
class MysqlSessionHistoryLiveTest extends JdbcSessionHistoryRepositoryTest {
    private JdbcTemplate admin;
    private String database;

    @Override
    @BeforeEach
    protected void setup() throws IOException {
        Properties config = new Properties();
        try (var input =
                     Files.newBufferedReader(
                             Path.of(
                                     System.getProperty(
                                             "horizen.mysql.recall.config", "../.env.yml")))) {
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
        admin = new JdbcTemplate(new DriverManagerDataSource(prefix + suffix, username, password));
        database = "ha_recall_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        initialize(
                new DriverManagerDataSource(prefix + database + suffix, username, password), false);
    }

    @AfterEach
    void removeDisposableDatabase() {
        if (admin != null && database != null) admin.execute("DROP DATABASE IF EXISTS " + database);
    }

    @Test
    void supplementaryUnicodeOffsetsCanContinueWithoutLosingCharacters() {
        String body = "😀".repeat(9000) + "尾部目标词";
        message("owner-a", "past", "emoji", "ASSISTANT", "FINAL", body);
        var hit = store.queryHistory(query(null, "尾部目标词", 5, 0)).orElseThrow().getEntries().get(0);
        assertTrue(hit.getContent().contains("尾部目标词"));
        var chunk =
                store.queryHistory(
                                new SessionHistoryQuery(
                                        "owner-a", "current", "past", null, "emoji", 4000, 1, 0))
                        .orElseThrow()
                        .getEntries()
                        .get(0);
        Assertions.assertEquals("😀".repeat(4000), chunk.getContent());
        Assertions.assertEquals(9005, chunk.getContentLength());
        Assertions.assertEquals(4000, chunk.getContentOffset());
    }
}
