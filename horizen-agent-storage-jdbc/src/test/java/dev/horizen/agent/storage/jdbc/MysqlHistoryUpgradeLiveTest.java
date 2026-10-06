package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.presentation.PresentationBlock;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcPresentationStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcTurnTimelineStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * 在已配置本地 MySQL 上使用独立临时库，不修改现有表。
 */
@EnabledIfSystemProperty(named = "horizen.mysql.history.live", matches = "true")
class MysqlHistoryUpgradeLiveTest {
    @Test
    void preservesOldCursorsAndBodiesThenAcceptsNewWrites() throws Exception {
        Properties config = new Properties();
        try (var input =
                     Files.newBufferedReader(
                             Path.of(
                                     System.getProperty(
                                             "horizen.mysql.history.config", "../.env.yml")))) {
            config.putAll(YamlConfigFiles.load(input));
        }
        String configured = config.getProperty("horizen.agent.storage.jdbc-url");
        URI uri = URI.create(configured.substring(5));
        assertTrue(
                List.of("127.0.0.1", "localhost").contains(uri.getHost()),
                "only local MySQL is allowed");
        String prefix = "jdbc:mysql://" + uri.getRawAuthority() + "/";
        String suffix = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String username = config.getProperty("horizen.agent.storage.jdbc-username");
        String password = config.getProperty("horizen.agent.storage.jdbc-password", "");
        var admin =
                new JdbcTemplate(new DriverManagerDataSource(prefix + suffix, username, password));
        String database = "ha_history_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        try {
            var source =
                    new DriverManagerDataSource(prefix + database + suffix, username, password);
            var jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(
                    new ClassPathResource("schema/legacy-history.sql"),
                    new ClassPathResource("schema/mysql.sql"))
                    .execute(source);
            Instant at = Instant.parse("2026-10-03T00:00:00Z");
            Timestamp stamp = Timestamp.from(at);
            jdbc.update(
                    "INSERT INTO ha_message VALUES (?,?,?,?,?,?,?,?,?,?)",
                    "owner",
                    "user-turn",
                    "session",
                    "turn",
                    "USER",
                    "FINAL",
                    "question",
                    1L,
                    stamp,
                    stamp);
            jdbc.update(
                    "INSERT INTO ha_message VALUES (?,?,?,?,?,?,?,?,?,?)",
                    "owner",
                    "assistant-turn",
                    "session",
                    "turn",
                    "ASSISTANT",
                    "FINAL",
                    "answer",
                    2L,
                    stamp,
                    stamp);
            var block = new PresentationBlock("card", "table", 1, 0, Map.of("title", "card body"));
            jdbc.update(
                    "INSERT INTO ha_presentation_block VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    "owner",
                    "card",
                    "session",
                    "turn",
                    "call",
                    "tool",
                    "table",
                    1,
                    0,
                    "{\"title\":\"card body\"}",
                    stamp);
            String card =
                    JdbcHistoryJson.encode(
                            Map.of(
                                    "type",
                                    "presentation_created",
                                    "details",
                                    JdbcHistoryJson.encode(
                                            Map.of("toolCallId", "call", "block", block))));
            String done =
                    "{\"type\":\"done\",\"source\":null,\"text\":\"【Mock 演示数据】\\n\\nanswer\"}";
            String tool = "{\"type\":\"tool_end\",\"details\":\"plain text result\"}";
            for (var entry : Map.of(40L, tool, 80L, card, 120L, done).entrySet()) {
                jdbc.update(
                        "INSERT INTO ha_turn_timeline_event VALUES (?,?,?,?,?,?)",
                        entry.getKey(),
                        "owner",
                        "session",
                        "turn",
                        entry.getValue(),
                        stamp);
            }
            new ResourceDatabasePopulator(
                    new ClassPathResource("schema/mysql-conversation-history-upgrade.sql"))
                    .execute(source);
            var timeline = new JdbcTurnTimelineStore(source);
            var history = timeline.listForSession("owner", "session");
            assertEquals(
                    List.of(40L, 80L, 120L), history.stream().map(e -> e.getSequence()).toList());
            assertEquals(
                    JdbcHistoryJson.object(done),
                    JdbcHistoryJson.object(history.get(2).getPayloadJson()));
            assertEquals(
                    "card body",
                    new JdbcPresentationStore(source)
                            .find("owner", "card")
                            .orElseThrow()
                            .getBlock()
                            .getData()
                            .get("title"));
            var sessions = new JdbcSessionTurnStore(source);
            assertEquals(
                    List.of("question", "answer"),
                    sessions.listFinalMessages("owner", "session").stream()
                            .map(m -> m.getContent())
                            .toList());
            assertEquals(
                    4,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ha_conversation_history", Integer.class));
            assertEquals(
                    3,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ha_turn_timeline_event",
                            Integer.class)); // 原始数据保持完整
            assertTrue(timeline.listForSession("other", "session").isEmpty());
            assertFalse(
                    jdbc.queryForObject(
                                    "SELECT timeline_payload_json FROM ha_conversation_history WHERE"
                                            + " record_type='PRESENTATION'",
                                    String.class)
                            .contains("card body"));
            sessions.startTurn(
                    new StartTurnCommand(
                            new ExecutionIdentity("owner", "actor"),
                            "new-session",
                            "new-turn",
                            "request",
                            "instance",
                            "new-user",
                            "question",
                            at,
                            at.plusSeconds(60),
                            at.plusSeconds(30)));
            sessions.transitionTurn(
                    new TransitionTurnCommand(
                            "owner",
                            "new-session",
                            "new-turn",
                            TurnStatus.COMPLETED,
                            null,
                            null,
                            at,
                            null,
                            "assistant-new-turn",
                            "new answer"));
            var newEvent =
                    timeline.append(
                            "owner",
                            "new-session",
                            "new-turn",
                            "{\"type\":\"done\",\"text\":\"new answer\"}",
                            at);
            assertTrue(newEvent.getSequence() > 120);
            assertEquals(
                    6,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ha_conversation_history", Integer.class));
            System.out.println(
                    "MySQL history upgrade: preserved cursors, one reply/card body, owner isolation, new"
                            + " writes passed");
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }
}
