package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.adapter.agentscope.workspace.document.WorkspaceDocumentBaseStore;
import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.web.bootstrap.runtime.AgentRuntimeFactory;
import dev.horizen.agent.web.bootstrap.runtime.WorkspaceRuntimeConfigurer;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

class CloudMemoryWorkspaceLiveTest {
    @TempDir Path workspace;

    @Test
    void cloudMemoryIsSharedAcrossSessionsAndAtomicAcrossInstances() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcWorkspaceDocumentRepository left =
                new JdbcWorkspaceDocumentRepository(
                        dataSource, InMemoryWorkspaceContentRepository.shared(dataSource));
        JdbcWorkspaceDocumentRepository right =
                new JdbcWorkspaceDocumentRepository(
                        dataSource, InMemoryWorkspaceContentRepository.shared(dataSource));
        AbstractFilesystem memory =
                new RemoteFilesystemSpec(new WorkspaceDocumentBaseStore(left))
                        .isolationScope(IsolationScope.USER)
                        .toFilesystem(
                                workspace,
                                AgentRuntimeFactory.AGENT_KEY,
                                IsolationScope.USER.toNamespaceFactory());
        RuntimeContext first = context("owner-a", "session-1");
        RuntimeContext second = context("owner-a", "session-2");
        RuntimeContext other = context("owner-b", "session-1");

        assertTrue(memory.write(first, "MEMORY.md", "- baseline\n").isSuccess());
        assertTrue(
                memory.read(second, "MEMORY.md", 0, 20).fileData().content().contains("baseline"));
        assertFalse(memory.exists(other, "MEMORY.md"));
        assertTrue(memory.edit(second, "MEMORY.md", "baseline", "edited", false).isSuccess());

        CloudMemoryService serviceA = new CloudMemoryService(left);
        CloudMemoryService serviceB = new CloudMemoryService(right);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                go.await();
                                return serviceA.save(
                                        "owner-a",
                                        AgentRuntimeFactory.AGENT_KEY,
                                        "tool-call-a",
                                        "- A fact");
                            });
            var b =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                go.await();
                                return serviceB.save(
                                        "owner-a",
                                        AgentRuntimeFactory.AGENT_KEY,
                                        "tool-call-b",
                                        "- B fact");
                            });
            ready.await();
            go.countDown();
            assertTrue(a.get().isApplied());
            assertTrue(b.get().isApplied());
        } finally {
            workers.shutdownNow();
        }
        String content = memory.read(second, "MEMORY.md", 0, 100).fileData().content();
        assertTrue(content.contains("edited"));
        assertTrue(content.contains("A fact"));
        assertTrue(content.contains("B fact"));
    }

    @Test
    void repeatedOperationAcrossMidnightDoesNotCreateAnotherDailyEntry() {
        JdbcWorkspaceDocumentRepository documents =
                new JdbcWorkspaceDocumentRepository(
                        dataSource(), InMemoryWorkspaceContentRepository.shared(dataSource()));
        Instant dayOne = Instant.parse("2026-09-30T23:59:59Z");
        Instant dayTwo = Instant.parse("2026-10-01T00:00:01Z");
        CloudMemoryService first =
                new CloudMemoryService(documents, Clock.fixed(dayOne, ZoneOffset.UTC));
        CloudMemoryService retry =
                new CloudMemoryService(documents, Clock.fixed(dayTwo, ZoneOffset.UTC));

        assertTrue(
                first.save("owner-a", AgentRuntimeFactory.AGENT_KEY, "save-once", "- fact")
                        .isApplied());
        assertFalse(
                retry.save("owner-a", AgentRuntimeFactory.AGENT_KEY, "save-once", "- fact")
                        .isApplied());
        first.replaceExactLine(
                "owner-a", AgentRuntimeFactory.AGENT_KEY, "replace-once", "- fact", "- updated");
        retry.replaceExactLine(
                "owner-a", AgentRuntimeFactory.AGENT_KEY, "replace-once", "- fact", "- updated");

        assertTrue(
                documents
                        .find(key("owner-a", "MEMORY.md"))
                        .orElseThrow()
                        .getContent()
                        .contains("- updated"));
        assertTrue(documents.find(key("owner-a", "memory/2026-09-30.md")).isEmpty());
        assertTrue(documents.find(key("owner-a", "memory/2026-10-01.md")).isEmpty());
    }

    @Test
    void cloudMemoryRouteDoesNotFallBackToAnInstanceLocalTemplate() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "local owner data must not leak");
        AbstractFilesystem memory =
                WorkspaceRuntimeConfigurer.cloudMemoryFilesystem(
                        new JdbcWorkspaceDocumentRepository(
                                dataSource(),
                                InMemoryWorkspaceContentRepository.shared(dataSource())),
                        "root");

        assertFalse(memory.read(context("owner-a", "session-1"), "MEMORY.md", 0, 10).isSuccess());
    }

    @Test
    void concurrentRetryOfTheSameMemoryUpdateIsIdempotent() throws Exception {
        JdbcWorkspaceDocumentRepository documents =
                new JdbcWorkspaceDocumentRepository(
                        dataSource(), InMemoryWorkspaceContentRepository.shared(dataSource()));
        CloudMemoryService first = new CloudMemoryService(documents);
        CloudMemoryService second = new CloudMemoryService(documents);
        first.save("owner-a", AgentRuntimeFactory.AGENT_KEY, "seed", "- old");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var left =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                await(go);
                                first.replaceExactLine(
                                        "owner-a",
                                        AgentRuntimeFactory.AGENT_KEY,
                                        "same-update",
                                        "- old",
                                        "- new");
                            });
            var right =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                await(go);
                                second.replaceExactLine(
                                        "owner-a",
                                        AgentRuntimeFactory.AGENT_KEY,
                                        "same-update",
                                        "- old",
                                        "- new");
                            });
            ready.await();
            go.countDown();
            left.get();
            right.get();
        } finally {
            workers.shutdownNow();
        }
        String content = documents.find(key("owner-a", "MEMORY.md")).orElseThrow().getContent();
        assertTrue(content.contains("- new"));
        assertFalse(content.contains("- old"));
    }

    private static RuntimeContext context(String owner, String session) {
        return RuntimeContext.builder().userId(owner).sessionId(session).build();
    }

    private static WorkspaceDocumentKey key(String owner, String path) {
        return new WorkspaceDocumentKey(
                owner, AgentRuntimeFactory.AGENT_KEY, CloudMemoryService.GLOBAL_SCOPE, path);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test worker interrupted", error);
        }
    }

    private static DriverManagerDataSource dataSource() {
        String database = "cloud_memory_" + UUID.randomUUID().toString().replace("-", "");
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
