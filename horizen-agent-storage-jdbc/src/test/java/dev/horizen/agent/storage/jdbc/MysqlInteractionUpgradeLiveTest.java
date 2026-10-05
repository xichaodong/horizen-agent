package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.interaction.approval.ApprovalDecisionCommand;
import dev.horizen.agent.interaction.approval.ApprovalDecisionResult;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.storage.jdbc.codec.JdbcInteractionJson;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcApprovalStore;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcAskUserStore;

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
import java.util.Properties;
import java.util.UUID;

/** 在独立的本地 MySQL 临时库中验证升级，完成后仅删除该临时库。 */
@EnabledIfSystemProperty(named = "horizen.mysql.interaction.live", matches = "true")
class MysqlInteractionUpgradeLiveTest {
    @Test
    void preservesRequestsStatusesVersionsAndResponsesThenResumesPendingInteractions()
            throws Exception {
        Properties config = new Properties();
        try (var input =
                Files.newBufferedReader(
                        Path.of(
                                System.getProperty(
                                        "horizen.mysql.interaction.config", "../.env.yml")))) {
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
        String database = "ha_interaction_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        try {
            var source =
                    new DriverManagerDataSource(prefix + database + suffix, username, password);
            var jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(
                            new ClassPathResource("schema/legacy-interaction.sql"),
                            new ClassPathResource("schema/mysql.sql"))
                    .execute(source);
            Instant at = Instant.parse("2026-10-03T00:00:00Z");
            Timestamp created = Timestamp.from(at);
            Timestamp resolved = Timestamp.from(at.plusSeconds(1));
            String args = "{ \"amount\": 42, \"note\": \"frozen\\nvalue\" }";
            for (ApprovalStatus status : ApprovalStatus.values()) {
                String id = status.name();
                boolean pending = status == ApprovalStatus.PENDING;
                jdbc.update(
                        """
            INSERT INTO ha_approval (owner_key,approval_id,session_id,turn_id,request_reply_id,
                tool_call_id,tool_name,tool_content,tool_arguments_json,presentation_json,status,
                requested_by,expires_at,decided_by,decided_at,created_at,updated_at,version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                        "owner",
                        id,
                        "session",
                        "turn",
                        pending ? null : "reply",
                        "call-" + id,
                        "synthetic_write",
                        "frozen description",
                        args,
                        pending ? null : "{\"title\":\"review\"}",
                        id,
                        "requester",
                        pending ? null : Timestamp.from(at.plusSeconds(60)),
                        pending ? null : "operator",
                        pending ? null : resolved,
                        created,
                        pending ? created : resolved,
                        pending ? 3 : 7);
            }
            for (AskUserStatus status : AskUserStatus.values()) {
                String id = status.name();
                boolean pending = status == AskUserStatus.PENDING;
                jdbc.update(
                        """
INSERT INTO ha_ask_user (owner_key,ask_user_id,session_id,turn_id,reply_id,
    tool_call_id,questions_json,answers_json,status,created_at,expires_at,resolved_at,version)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
                        "owner",
                        id,
                        "session",
                        "turn",
                        pending ? null : "reply",
                        "call-" + id,
                        "[{\"question\":\"scope?\"}]",
                        status == AskUserStatus.ANSWERED ? "[\"full\"]" : "[]",
                        id,
                        created,
                        Timestamp.from(at.plusSeconds(60)),
                        pending ? null : resolved,
                        pending ? 4 : 8);
            }
            new ResourceDatabasePopulator(
                            new ClassPathResource("schema/mysql-interaction-upgrade.sql"))
                    .execute(source);
            var approvals = new JdbcApprovalStore(source);
            var asks = new JdbcAskUserStore(source);
            assertEquals(
                    10, jdbc.queryForObject("SELECT COUNT(*) FROM ha_interaction", Integer.class));
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM ha_approval", Integer.class));
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM ha_ask_user", Integer.class));
            var pending = approvals.findPending("owner", "session", "turn").get(0);
            assertEquals(3, pending.getVersion());
            assertEquals(args, pending.getToolArgumentsJson());
            assertNull(pending.getRequestReplyId());
            assertNull(pending.getExpiresAt());
            assertNull(pending.getPresentationJson());
            assertNull(pending.getDecidedBy());
            assertEquals(4, asks.find("owner", "PENDING").orElseThrow().getVersion());
            for (ApprovalStatus status : ApprovalStatus.values()) {
                if (status == ApprovalStatus.PENDING) continue;
                var stored =
                        approvals.decide(
                                new ApprovalDecisionCommand(
                                        "owner", status.name(), false, "new-operator", at));
                assertEquals(ApprovalDecisionResult.Outcome.ALREADY_DECIDED, stored.getOutcome());
                assertEquals(status, stored.getApproval().getStatus());
                if (status == ApprovalStatus.APPROVED || status == ApprovalStatus.DENIED) {
                    var response =
                            JdbcInteractionJson.read(
                                    jdbc.queryForObject(
                                            "SELECT response_json FROM ha_interaction WHERE interaction_type='APPROVAL'"
                                                    + " AND interaction_id=?",
                                            String.class,
                                            status.name()));
                    assertTrue(response.path("approved").isBoolean());
                    assertEquals(
                            status == ApprovalStatus.APPROVED,
                            response.path("approved").asBoolean());
                }
                assertEquals(7, stored.getApproval().getVersion());
                assertEquals("operator", stored.getApproval().getDecidedBy());
                assertEquals(at.plusSeconds(1), stored.getApproval().getDecidedAt());
                assertEquals("{\"title\":\"review\"}", stored.getApproval().getPresentationJson());
            }
            for (AskUserStatus status : AskUserStatus.values()) {
                var stored = asks.find("owner", status.name()).orElseThrow();
                assertEquals(status, stored.getStatus());
                if (status != AskUserStatus.PENDING) {
                    assertEquals(8, stored.getVersion());
                    assertEquals(at.plusSeconds(1), stored.getResolvedAt());
                }
            }
            assertEquals(
                    "[\"full\"]", asks.find("owner", "ANSWERED").orElseThrow().getAnswersJson());
            String frozen =
                    jdbc.queryForObject(
                            "SELECT request_json FROM ha_interaction WHERE interaction_type='APPROVAL' AND"
                                    + " interaction_id='PENDING'",
                            String.class);
            var updated =
                    approvals
                            .decide(
                                    new ApprovalDecisionCommand(
                                            "owner",
                                            "PENDING",
                                            true,
                                            "new-operator",
                                            at.plusSeconds(2)))
                            .getApproval();
            assertEquals(4, updated.getVersion());
            assertEquals(args, updated.getToolArgumentsJson());
            assertEquals(
                    frozen,
                    jdbc.queryForObject(
                            "SELECT request_json FROM ha_interaction WHERE interaction_type='APPROVAL' AND"
                                    + " interaction_id='PENDING'",
                            String.class));
            assertEquals(
                    AskUserStatus.PENDING, asks.find("owner", "PENDING").orElseThrow().getStatus());
            asks.resolve(
                    "owner",
                    "PENDING",
                    AskUserStatus.ANSWERED,
                    "[\"tags_only\"]",
                    at.plusSeconds(2),
                    4);
            assertEquals(5, asks.find("owner", "PENDING").orElseThrow().getVersion());
            assertTrue(approvals.findPending("other", "session", "turn").isEmpty());
            assertTrue(asks.find("other", "PENDING").isEmpty());
            System.out.println(
                    "MySQL interaction upgrade: all statuses, frozen requests, IDs/versions, pending resume"
                            + " and type/owner isolation passed");
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }
}
