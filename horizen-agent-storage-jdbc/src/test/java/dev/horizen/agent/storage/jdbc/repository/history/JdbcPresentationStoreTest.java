package dev.horizen.agent.storage.jdbc.repository.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.domain.presentation.PresentationBlock;
import dev.horizen.agent.domain.presentation.PresentationRecord;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.Map;

class JdbcPresentationStoreTest {
    private JdbcPresentationStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:presentation-"
                        + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(dataSource);
        store = new JdbcPresentationStore(dataSource);
    }

    @Test
    void persistsIdempotentlyAndIsolatesOwners() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        PresentationRecord record =
                new PresentationRecord(
                        "owner-a",
                        "session",
                        "turn",
                        "call",
                        "diagnose",
                        new PresentationBlock("ui_1", "issue", 1, 0, Map.of("title", "流量下跌")),
                        now);

        assertEquals("流量下跌", store.createOrFind(record).getBlock().getData().get("title"));
        assertEquals("ui_1", store.createOrFind(record).getBlock().getBlockId());
        assertEquals(1, store.listForSession("owner-a", "session").size());
        assertTrue(store.listForSession("owner-b", "session").isEmpty());
        assertTrue(store.find("owner-b", "ui_1").isEmpty());
    }

    @Test
    void rejectsReusingAnIdWithDifferentContent() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        store.createOrFind(
                new PresentationRecord(
                        "owner",
                        "session",
                        "turn",
                        "call",
                        "tool",
                        new PresentationBlock("ui_1", "issue", 1, 0, Map.of("title", "one")),
                        now));
        PresentationRecord changed =
                new PresentationRecord(
                        "owner",
                        "session",
                        "turn",
                        "call",
                        "tool",
                        new PresentationBlock("ui_1", "issue", 1, 0, Map.of("title", "two")),
                        now);
        assertThrows(IllegalStateException.class, () -> store.createOrFind(changed));
    }

    @Test
    void treatsJsonEquivalentLongAndIntegerPayloadsAsIdempotent() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        PresentationRecord artifact =
                new PresentationRecord(
                        "owner",
                        "session",
                        "turn",
                        "call",
                        "deliver_artifact",
                        new PresentationBlock(
                                "ui_art",
                                "artifact_card",
                                1,
                                0,
                                Map.of("artifactId", "artifact-1", "sizeBytes", 4087L)),
                        now);

        PresentationRecord stored = store.createOrFind(artifact);
        assertEquals(4087, ((Number) stored.getBlock().getData().get("sizeBytes")).intValue());
        assertEquals("ui_art", store.createOrFind(artifact).getBlock().getBlockId());
    }
}
