package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.web.LiveConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

@EnabledIfSystemProperty(named = "horizen.workspace.memory.live", matches = "true")
class WorkspaceMemoryConfiguredLiveTest {
    @Test
    void realMysqlEnforcesConcurrentAppendCasIdempotencyAndStructuredAudit() throws Exception {
        Properties p = new Properties();
        try (var input = Files.newInputStream(Path.of("..", ".env.yml"))) {
            p.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        URI uri = URI.create(p.getProperty("horizen.agent.storage.jdbc-url").substring(5));
        assertTrue(List.of("127.0.0.1", "localhost").contains(uri.getHost()));
        String base = "jdbc:mysql://" + uri.getRawAuthority() + "/";
        String suffix = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String username = p.getProperty("horizen.agent.storage.jdbc-username"),
                password = p.getProperty("horizen.agent.storage.jdbc-password", "");
        var admin =
                new JdbcTemplate(new DriverManagerDataSource(base + suffix, username, password));
        String database = "ha_memory_live_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        var source = new DriverManagerDataSource(base + database + suffix, username, password);
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        String owner = "workspace-memory-live-" + UUID.randomUUID().toString().replace("-", "");
        String agent = "memory-acceptance";
        var documents =
                new JdbcWorkspaceDocumentRepository(
                        source, InMemoryWorkspaceContentRepository.shared(source));
        var second =
                new JdbcWorkspaceDocumentRepository(
                        source, InMemoryWorkspaceContentRepository.shared(source));
        var memory = new CloudMemoryService(documents);
        var other = new CloudMemoryService(second);
        var key = new WorkspaceDocumentKey(owner, agent, "global", "MEMORY.md");
        var workers = Executors.newFixedThreadPool(2);
        try {
            var go = new CountDownLatch(1);
            var a =
                    workers.submit(
                            () -> {
                                go.await();
                                return memory.save(owner, agent, "call-a", "- alpha");
                            });
            var b =
                    workers.submit(
                            () -> {
                                go.await();
                                return other.save(owner, agent, "call-b", "- beta");
                            });
            go.countDown();
            assertTrue(a.get().isApplied());
            assertTrue(b.get().isApplied());
            var before = documents.find(key).orElseThrow();
            assertTrue(before.getContent().contains("- alpha"));
            assertTrue(before.getContent().contains("- beta"));
            assertFalse(memory.save(owner, agent, "call-a", "- alpha").isApplied());
            assertEquals(before.getContent(), documents.find(key).orElseThrow().getContent());
            memory.replaceExactLine(owner, agent, "update-a", "- alpha", "- revised");
            assertFalse(documents.replace(key, "stale", before.getVersion()));
            assertTrue(documents.find(key).orElseThrow().getContent().contains("- revised"));
            var limited =
                    new JdbcWorkspaceDocumentRepository(
                            source, InMemoryWorkspaceContentRepository.shared(source), 32);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new CloudMemoryService(limited)
                                    .save(
                                            owner,
                                            "quota-acceptance",
                                            "quota",
                                            "- " + "x".repeat(40)));
            assertTrue(
                    documents
                            .find(
                                    new WorkspaceDocumentKey(
                                            owner, "quota-acceptance", "global", "MEMORY.md"))
                            .isEmpty());
            System.out.println(
                    "Workspace memory live: concurrent append, idempotency, CAS and structured audit passed"
                            + " on real MySQL");
        } finally {
            workers.shutdownNow();
            var jdbc = new JdbcTemplate(source);
            for (String table : List.of("ha_workspace_operation", "ha_workspace_file")) {
                jdbc.update("DELETE FROM " + table + " WHERE owner_key=?", owner);
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM " + table + " WHERE owner_key=?",
                                Integer.class,
                                owner));
            }
            admin.execute("DROP DATABASE " + database);
            System.out.println("Workspace memory live: test rows cleaned and verified");
        }
    }
}
