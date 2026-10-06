package dev.horizen.agent.storage.jdbc.repository.session;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.execution.session.SessionHistoryQuery;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * H2 执行真实的作用域 SQL；别名仅补充 MySQL 的 JSON 标量函数。
 */
public class JdbcSessionHistoryRepositoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private JdbcTemplate jdbc;
    protected JdbcSessionTurnStore store;
    private DataSource source;
    private int messageNumber;

    @BeforeEach
    protected void setup() throws Exception {
        initialize(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:recall_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        ""),
                true);
    }

    protected void initialize(DataSource dataSource, boolean h2) {
        source = dataSource;
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        if (h2) {
            jdbc.execute(
                    "CREATE ALIAS JSON_EXTRACT FOR 'dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionHistoryRepositoryTest.jsonExtract'");
            jdbc.execute(
                    "CREATE ALIAS JSON_UNQUOTE FOR 'dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionHistoryRepositoryTest.jsonUnquote'");
        }
        store = new JdbcSessionTurnStore(source);
        session("owner-a", "current", 1L, "agent-a", "当前分析", "ACTIVE");
        session("owner-a", "past", 1L, "agent-a", "Excel销售分析", "ACTIVE");
        session("owner-b", "past", 1L, "agent-a", "其他用户", "ACTIVE");
        session("owner-a", "other-project", 2L, "agent-a", "其他项目", "ACTIVE");
        session("owner-a", "other-agent", 1L, "agent-b", "其他Agent", "ACTIVE");
        session("owner-a", "deleted", 1L, "agent-a", "已删除", "ARCHIVED");
        session("owner-a", "legacy", null, null, "未绑定会话", "ACTIVE");
        message("owner-a", "current", "current-message", "USER", "FINAL", "Excel 当前问题");
        message("owner-a", "past", "past-user", "USER", "FINAL", "Excel按华东区域过滤");
        message("owner-a", "past", "past-assistant", "ASSISTANT", "FINAL", "汇总结果包含销售明细");
        message("owner-b", "past", "private-owner", "USER", "FINAL", "Excel OTHER_OWNER_PRIVATE");
        message(
                "owner-a",
                "other-project",
                "private-project",
                "USER",
                "FINAL",
                "Excel OTHER_PROJECT_PRIVATE");
        message(
                "owner-a",
                "other-agent",
                "private-agent",
                "USER",
                "FINAL",
                "Excel OTHER_AGENT_PRIVATE");
        message("owner-a", "deleted", "private-deleted", "USER", "FINAL", "Excel DELETED_PRIVATE");
        message("owner-a", "legacy", "private-unbound", "USER", "FINAL", "Excel UNBOUND_PRIVATE");
        message("owner-a", "past", "draft", "ASSISTANT", "DRAFT", "Excel DRAFT_PRIVATE");
        message("owner-a", "past", "system", "SYSTEM", "FINAL", "Excel SYSTEM_PRIVATE");
    }

    @Test
    void recallIsOwnerCatalogAndVisibilityScopedAndSearchesTitles() {
        var page = store.queryHistory(query(null, "excel", 20, 0)).orElseThrow();
        assertEquals(2, page.getEntries().size());
        assertTrue(
                page.getEntries().stream().allMatch(entry -> entry.getSessionId().equals("past")));
        assertTrue(
                page.getEntries().stream()
                        .noneMatch(entry -> entry.getContent().contains("PRIVATE")));
        assertEquals("past-assistant", page.getEntries().get(0).getMessageId());
        // 另一个适配器实例读取相同的持久化事实，不依赖 Redis 或本地对话记录。
        assertEquals(
                page,
                new JdbcSessionTurnStore(source)
                        .queryHistory(query(null, "excel", 20, 0))
                        .orElseThrow());
    }

    @Test
    void explicitSessionReadsHaveTheSameAuthorizationAsSearch() {
        for (String target :
                new String[]{"other-project", "other-agent", "deleted", "legacy", "missing"}) {
            assertTrue(store.queryHistory(query(target, null, 10, 0)).isEmpty(), target);
        }
        var page = store.queryHistory(query("past", null, 10, 0)).orElseThrow();
        assertEquals(2L, page.getTotalMessages());
        assertEquals("past-user", page.getEntries().get(0).getMessageId());
        assertTrue(
                store.queryHistory(
                                new SessionHistoryQuery(
                                        "owner-b", "current", "past", null, null, 0, 10, 0))
                        .isEmpty());
    }

    @Test
    void literalWildcardsAndEscapedJsonAreNotQuerySyntaxOrMetadataMatches() {
        message("owner-a", "past", "special", "USER", "FINAL", "折扣50%_!，引号\"中文\"，路径C:\\reports");
        for (String keyword : new String[]{"50%_!", "\"中文\"", "C:\\reports"}) {
            var page = store.queryHistory(query(null, keyword, 20, 0)).orElseThrow();
            assertEquals(1, page.getEntries().size(), keyword);
            assertEquals("special", page.getEntries().get(0).getMessageId());
        }
        assertTrue(
                store.queryHistory(query(null, "past-assistant", 20, 0))
                        .orElseThrow()
                        .getEntries()
                        .isEmpty());
        assertTrue(
                store.queryHistory(query(null, "' OR 1=1 --", 20, 0))
                        .orElseThrow()
                        .getEntries()
                        .isEmpty());
    }

    @Test
    void keywordPaginationAndCurrentSessionFilterAreExplicit() {
        var first = store.queryHistory(query(null, "Excel", 1, 0)).orElseThrow();
        assertTrue(first.isHasMore());
        assertEquals(1, first.getNextOffset());
        var second =
                store.queryHistory(query(null, "Excel", 1, first.getNextOffset())).orElseThrow();
        assertFalse(second.isHasMore());
        assertNotEquals(
                first.getEntries().get(0).getMessageId(),
                second.getEntries().get(0).getMessageId());
        assertEquals(
                "current-message",
                store.queryHistory(query("current", "Excel", 10, 0))
                        .orElseThrow()
                        .getEntries()
                        .get(0)
                        .getMessageId());
    }

    @Test
    void longMessagesAreChunkedAndTailMatchesReturnTheRelevantExcerpt() {
        String body = "中".repeat(9000) + "尾部目标词";
        message("owner-a", "past", "long", "ASSISTANT", "FINAL", body);
        var hit = store.queryHistory(query(null, "尾部目标词", 5, 0)).orElseThrow().getEntries().get(0);
        assertTrue(hit.getContent().contains("尾部目标词"));
        assertTrue(hit.getContentOffset() > 8000);
        assertTrue(hit.getContent().length() <= 480);
        var read =
                store.queryHistory(
                                new SessionHistoryQuery(
                                        "owner-a", "current", "past", null, "long", 4000, 1, 0))
                        .orElseThrow()
                        .getEntries()
                        .get(0);
        assertEquals(4000, read.getContentOffset());
        assertEquals(4000, read.getContent().length());
        assertEquals(body.length(), read.getContentLength());
    }

    @Test
    void unboundSessionsDoNotAcquireAccessToBoundCatalogs() {
        session("owner-a", "legacy-current", null, null, "未绑定当前", "ACTIVE");
        var page =
                store.queryHistory(
                                new SessionHistoryQuery(
                                        "owner-a", "legacy-current", null, "Excel", null, 0, 20, 0))
                        .orElseThrow();
        assertEquals(1, page.getEntries().size());
        assertEquals("legacy", page.getEntries().get(0).getSessionId());
        assertTrue(
                store.queryHistory(
                                new SessionHistoryQuery(
                                        "owner-a", "legacy-current", "past", null, null, 0, 10, 0))
                        .isEmpty());
    }

    @Test
    void partiallyBoundCatalogStillCannotEscapeProjectScope() {
        // 即使旧记录缺少完整发布绑定，也以目录列为准。
        session("owner-a", "partial-current", 3L, null, "半绑定当前", "ACTIVE");
        session("owner-a", "partial-past", 3L, null, "半绑定历史", "ACTIVE");
        message("owner-a", "partial-past", "partial", "USER", "FINAL", "Excel合法历史");
        var page =
                store.queryHistory(
                                new SessionHistoryQuery(
                                        "owner-a",
                                        "partial-current",
                                        null,
                                        "Excel",
                                        null,
                                        0,
                                        20,
                                        0))
                        .orElseThrow();
        assertEquals(1, page.getEntries().size());
        assertEquals("partial-past", page.getEntries().get(0).getSessionId());
    }

    protected SessionHistoryQuery query(String session, String keyword, int limit, int offset) {
        return new SessionHistoryQuery(
                "owner-a", "current", session, keyword, null, 0, limit, offset);
    }

    private void session(
            String owner, String id, Long project, String agent, String title, String status) {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-28T00:00:00Z"));
        jdbc.update(
                "INSERT INTO ha_session(owner_key,session_id,status,created_by,title,last_message_at,created_at,updated_at,version,project_id,agent_key) VALUES(?,?,?,?,?,?,?,?,0,?,?)",
                owner,
                id,
                status,
                owner,
                title,
                now,
                now,
                now,
                project,
                agent);
    }

    protected void message(
            String owner, String session, String id, String role, String status, String content) {
        Timestamp now =
                Timestamp.from(Instant.parse("2026-09-28T00:00:00Z").plusSeconds(++messageNumber));
        jdbc.update(
                "INSERT INTO ha_conversation_history(owner_key,session_id,turn_id,record_type,record_id,message_sequence,payload_json,created_at,updated_at) VALUES(?,?,?,'MESSAGE',?,?,?,?,?)",
                owner,
                session,
                "turn-" + id,
                id,
                messageNumber,
                JdbcHistoryJson.encode(Map.of("role", role, "status", status, "content", content)),
                now,
                now);
    }

    public static String jsonExtract(String document, String path) throws Exception {
        var node = JSON.readTree(document).get(path.substring(2));
        return node == null ? null : node.toString();
    }

    public static String jsonUnquote(String value) throws Exception {
        if (value == null) return null;
        var node = JSON.readTree(value);
        return node.isTextual() ? node.textValue() : node.toString();
    }
}
