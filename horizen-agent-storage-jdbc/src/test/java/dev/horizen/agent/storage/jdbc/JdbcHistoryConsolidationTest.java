package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.presentation.PresentationBlock;
import dev.horizen.agent.domain.presentation.PresentationRecord;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcPresentationStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcTurnTimelineStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

class JdbcHistoryConsolidationTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private JdbcSessionTurnStore sessions;
    private JdbcTurnTimelineStore timeline;
    private JdbcPresentationStore cards;
    private final Instant now = Instant.parse("2026-10-03T00:00:00Z");

    @BeforeEach
    void prepare() {
        source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:history-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        sessions = new JdbcSessionTurnStore(source);
        timeline = new JdbcTurnTimelineStore(source);
        cards = new JdbcPresentationStore(source);
    }

    @Test
    void completeReplyIsStoredOnceAndRestoresBothViewsAfterRestart() {
        start("owner", "session", "turn", "question");
        timeline.append(
                "owner",
                "session",
                "turn",
                "{\"type\":\"tool_end\",\"details\":\"complete tool result\"}",
                now);
        sessions.transitionTurn(
                new TransitionTurnCommand(
                        "owner",
                        "session",
                        "turn",
                        TurnStatus.COMPLETED,
                        null,
                        null,
                        now.plusSeconds(1),
                        null,
                        "host-defined-reply-id",
                        "complete answer"));
        String event =
                JdbcHistoryJson.encode(
                        Map.of("type", "done", "text", "【Mock 演示数据】\n\ncomplete answer"));
        var saved = timeline.append("owner", "session", "turn", event, now.plusSeconds(2));
        assertEquals(3, count()); // 用户消息 + 工具事实 + 最终回复（包含事件信封）。
        String stored =
                jdbc.queryForObject(
                        "SELECT timeline_payload_json FROM ha_conversation_history WHERE record_type='MESSAGE'"
                                + " AND record_id='host-defined-reply-id'",
                        String.class);
        assertFalse(stored.contains("complete answer"));
        var restored = new JdbcTurnTimelineStore(source).listForSession("owner", "session");
        assertEquals(saved, restored.get(1));
        assertEquals(
                "complete answer",
                new JdbcSessionTurnStore(source)
                        .listFinalMessages("owner", "session")
                        .get(1)
                        .getContent());
        assertTrue(new JdbcTurnTimelineStore(source).listForSession("other", "session").isEmpty());
        assertEquals(
                List.of(1L, 2L),
                sessions.listFinalMessages("owner", "session").stream()
                        .map(m -> m.getSequence())
                        .toList());
        assertTrue(restored.get(1).getSequence() > restored.get(0).getSequence());
    }

    @Test
    void cardAndItsTimelineEventShareOneRowAndConcurrentReplaysRemainIdempotent() throws Exception {
        var block =
                new PresentationBlock(
                        "card", "table", 1, 0, Map.of("title", "full card data", "size", 42L));
        cards.createOrFind(
                new PresentationRecord("owner", "session", "turn", "call", "tool", block, now));
        String event =
                JdbcHistoryJson.encode(
                        Map.of(
                                "type",
                                "presentation_created",
                                "details",
                                JdbcHistoryJson.encode(
                                        Map.of("toolCallId", "call", "block", block))));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> timeline.append("owner", "session", "turn", event, now));
            var second = pool.submit(() -> timeline.append("owner", "session", "turn", event, now));
            assertEquals(first.get().getSequence(), second.get().getSequence());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count());
        assertFalse(
                jdbc.queryForObject(
                                "SELECT timeline_payload_json FROM ha_conversation_history",
                                String.class)
                        .contains("full card data"));
        assertEquals(
                "full card data",
                new JdbcPresentationStore(source)
                        .find("owner", "card")
                        .orElseThrow()
                        .getBlock()
                        .getData()
                        .get("title"));
        var restored =
                JdbcHistoryJson.object(
                        timeline.listForSession("owner", "session").get(0).getPayloadJson());
        assertEquals("presentation_created", restored.path("type").asText());
        assertEquals(
                JdbcHistoryJson.object(JdbcHistoryJson.object(event).path("details").asText()),
                JdbcHistoryJson.object(restored.path("details").asText()));
    }

    @Test
    void childResultCannotOverwriteTheRootReplyAndStandaloneEventsStillWork() {
        start("owner", "session", "turn", "question");
        sessions.transitionTurn(
                new TransitionTurnCommand(
                        "owner",
                        "session",
                        "turn",
                        TurnStatus.COMPLETED,
                        null,
                        null,
                        now,
                        null,
                        "assistant-turn",
                        "root reply"));
        timeline.append(
                "owner",
                "session",
                "turn",
                "{\"type\":\"done\",\"source\":\"child\",\"text\":\"child reply\"}",
                now);
        assertEquals(3, count());
        assertEquals(
                "child reply",
                JdbcHistoryJson.object(
                                timeline.listForSession("owner", "session").get(0).getPayloadJson())
                        .path("text")
                        .asText());
        assertNull(
                jdbc.queryForObject(
                        "SELECT timeline_sequence FROM ha_conversation_history WHERE"
                                + " record_id='assistant-turn'",
                        Long.class));
    }

    @Test
    void messageFailureRollsBackTurnAndDoesNotConsumeASequence() {
        start("owner", "session", "turn", "question");
        assertThrows(
                RuntimeException.class,
                () ->
                        sessions.transitionTurn(
                                new TransitionTurnCommand(
                                        "owner",
                                        "session",
                                        "turn",
                                        TurnStatus.COMPLETED,
                                        null,
                                        null,
                                        now,
                                        null,
                                        "user-turn",
                                        "duplicate message id")));
        assertEquals(
                TurnStatus.RUNNING, sessions.findTurn("owner", "turn").orElseThrow().getStatus());
        assertEquals(1, count());
        assertEquals(
                2L,
                jdbc.queryForObject("SELECT next_message_sequence FROM ha_session", Long.class));
    }

    @Test
    void sameIdentifierAcrossKindsAndOwnersDoesNotCollide() {
        start("owner", "session", "turn", "question");
        var block = new PresentationBlock("user-turn", "table", 1, 0, Map.of("title", "card"));
        cards.createOrFind(
                new PresentationRecord("owner", "session", "turn", "call", "tool", block, now));
        cards.createOrFind(
                new PresentationRecord("other", "session", "turn", "call", "tool", block, now));
        assertEquals(3, count());
        assertEquals(1, sessions.listFinalMessages("owner", "session").size());
        assertEquals(1, cards.listForSession("owner", "session").size());
        assertTrue(timeline.listForSession("owner", "session").isEmpty());
    }

    @Test
    void draftMessagesRemainExcludedFromFormalHistory() {
        jdbc.update(
                """
                        INSERT INTO ha_conversation_history (owner_key, session_id, turn_id, record_type,
                            record_id, message_sequence, payload_json, created_at, updated_at)
                        VALUES ('owner', 'session', 'turn', 'MESSAGE', 'draft', 1, ?, ?, ?)
                        """,
                "{\"role\":\"ASSISTANT\",\"status\":\"DRAFT\",\"content\":\"unfinished\"}",
                Timestamp.from(now),
                Timestamp.from(now));
        assertTrue(sessions.listFinalMessages("owner", "session").isEmpty());
    }

    private void start(String owner, String session, String turn, String text) {
        sessions.startTurn(
                new StartTurnCommand(
                        new ExecutionIdentity(owner, "actor"),
                        session,
                        turn,
                        "request",
                        "instance",
                        "user-" + turn,
                        text,
                        now,
                        now.plusSeconds(60),
                        now.plusSeconds(30)));
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ha_conversation_history", Integer.class);
    }
}
