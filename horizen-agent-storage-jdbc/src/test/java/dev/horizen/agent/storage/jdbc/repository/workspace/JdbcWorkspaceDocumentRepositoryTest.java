package dev.horizen.agent.storage.jdbc.repository.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentAppend;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentCommit;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentReplace;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

class JdbcWorkspaceDocumentRepositoryTest {
    @Test
    void appendsAreIdempotentAndConcurrentWritersDoNotLoseEntries() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcWorkspaceDocumentRepository left =
                new JdbcWorkspaceDocumentRepository(
                        dataSource, InMemoryWorkspaceContentRepository.shared(dataSource));
        JdbcWorkspaceDocumentRepository right =
                new JdbcWorkspaceDocumentRepository(
                        dataSource, InMemoryWorkspaceContentRepository.shared(dataSource));
        WorkspaceDocumentKey key = key("owner-a", "MEMORY.md");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var one =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                go.await();
                                return left.append(key, "call-1", "- one\n");
                            });
            var two =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                go.await();
                                return right.append(key, "call-2", "- two\n");
                            });
            ready.await();
            go.countDown();
            assertTrue(one.get().isApplied());
            assertTrue(two.get().isApplied());
        } finally {
            workers.shutdownNow();
        }

        String content = left.find(key).orElseThrow().getContent();
        assertTrue(content.contains("- one\n"));
        assertTrue(content.contains("- two\n"));
        assertFalse(left.append(key, "call-1", "- duplicated\n").isApplied());
        assertEquals(content, left.find(key).orElseThrow().getContent());
    }

    @Test
    void conditionalReplaceAndOwnerQuotaAreEnforced() {
        JdbcWorkspaceDocumentRepository documents =
                new JdbcWorkspaceDocumentRepository(
                        dataSource(), new InMemoryWorkspaceContentRepository(), 32);
        WorkspaceDocumentKey ownerA = key("owner-a", "MEMORY.md");
        WorkspaceDocumentKey ownerB = key("owner-b", "MEMORY.md");
        assertTrue(documents.createIfAbsent(ownerA, "first"));
        long version = documents.find(ownerA).orElseThrow().getVersion();
        assertTrue(documents.replace(ownerA, "second", version));
        assertFalse(documents.replace(ownerA, "stale", version));
        assertTrue(documents.createIfAbsent(ownerB, "independent"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        documents.append(
                                key("owner-a", "memory/2026-09-30.md"),
                                "call-cap",
                                "x".repeat(36)));
        assertThrows(
                IllegalArgumentException.class,
                () -> documents.createIfAbsent(key("owner-a", "AGENTS.md"), "server managed"));
    }

    @Test
    void commitRollsBackEveryDocumentWhenItsAuditCannotFit() {
        var contents = new InMemoryWorkspaceContentRepository();
        JdbcWorkspaceDocumentRepository documents =
                new JdbcWorkspaceDocumentRepository(dataSource(), contents, 32);
        WorkspaceDocumentKey memory = key("owner-a", "MEMORY.md");
        WorkspaceDocumentKey daily = key("owner-a", "memory/2026-09-30.md");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        documents.commit(
                                new WorkspaceDocumentCommit(
                                        null,
                                        List.of(
                                                new WorkspaceDocumentAppend(
                                                        memory, "save:memory", "- fact\n"),
                                                new WorkspaceDocumentAppend(
                                                        daily, "save:daily", "x".repeat(33))))));
        assertTrue(documents.find(memory).isEmpty());
        assertTrue(documents.find(daily).isEmpty());
        assertEquals(0, contents.objectCount());

        assertTrue(documents.createIfAbsent(memory, "old\n"));
        long version = documents.find(memory).orElseThrow().getVersion();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        documents.commit(
                                new WorkspaceDocumentCommit(
                                        new WorkspaceDocumentReplace(memory, "new\n", version),
                                        List.of(
                                                new WorkspaceDocumentAppend(
                                                        memory, "replace:marker", ""),
                                                new WorkspaceDocumentAppend(
                                                        daily, "replace:audit", "x".repeat(33))))));
        assertEquals("old\n", documents.find(memory).orElseThrow().getContent());
        assertTrue(documents.find(daily).isEmpty());
        assertEquals(1, contents.objectCount());
    }

    private static WorkspaceDocumentKey key(String owner, String path) {
        return new WorkspaceDocumentKey(owner, "horizen-web-agent", "global", path);
    }

    private static DriverManagerDataSource dataSource() {
        String database = "workspace_document_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + database
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        return source;
    }
}
