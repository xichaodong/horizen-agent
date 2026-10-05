package dev.horizen.agent.adapter.workspace.horizen;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.AgentReleaseSnapshot;
import dev.horizen.agent.domain.workspace.release.ReleaseManifestCanonicalizer;
import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class PublicationConcurrencyTest {
    @TempDir Path root;

    private static AgentReleaseManifest manifest(int version) {
        byte[] content = ("AGENTS R" + version).getBytes(StandardCharsets.UTF_8);
        String checksum = DigestUtils.sha256Hex(content);
        String hash =
                ReleaseManifestCanonicalizer.workspaceHash(
                        7,
                        "test-agent",
                        List.of(
                                new ReleaseManifestCanonicalizer.ContentEntry(
                                        "AGENTS.md", checksum, content.length)));
        return new AgentReleaseManifest(
                7,
                "test-agent",
                version,
                version,
                hash,
                List.of(
                        new AgentReleaseManifest.Asset(
                                "AGENTS.md",
                                "workspace-object:v" + version,
                                checksum,
                                content.length,
                                "text/plain")));
    }

    private HorizenAgentReleaseRepository repository(
            WorkspaceContentRepository contents, long bytes) {
        var catalog =
                (WorkspaceCatalogRepository)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {WorkspaceCatalogRepository.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError("Catalog is not consulted by acquire");
                                });
        return new HorizenAgentReleaseRepository(null, root, bytes, catalog, contents);
    }

    private abstract static class Contents implements WorkspaceContentRepository {
        public String upload(WorkspaceDocumentKey key, byte[] content) {
            throw new UnsupportedOperationException();
        }

        public void delete(String reference) {
            throw new UnsupportedOperationException();
        }

        byte[] body(String reference) {
            return ("AGENTS R" + reference.substring(1)).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    void aSlowColdVersionDoesNotBlockAnotherVersionOrAWarmHit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var repo =
                repository(
                        new Contents() {
                            public byte[] download(String reference, long limit) {
                                if (reference.equals("v1")) {
                                    entered.countDown();
                                    try {
                                        if (!release.await(5, TimeUnit.SECONDS))
                                            throw new AssertionError("Download test timed out");
                                    } catch (InterruptedException error) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(error);
                                    }
                                }
                                return body(reference);
                            }
                        },
                        128L * 1024 * 1024);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Future<AgentReleaseSnapshot> slow = workers.submit(() -> repo.acquire(manifest(1)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<AgentReleaseSnapshot> other = workers.submit(() -> repo.acquire(manifest(2)));
            try (var second = other.get(2, TimeUnit.SECONDS);
                    var warm = repo.acquire(manifest(2))) {
                assertEquals(
                        "AGENTS R2",
                        new String(warm.open("AGENTS.md").readAllBytes(), StandardCharsets.UTF_8));
                assertFalse(slow.isDone());
            }
            release.countDown();
            slow.get(2, TimeUnit.SECONDS).close();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void sameVersionDownloadsOnlyOnceAndActiveLeasesCannotBeEvicted() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var downloads = new AtomicInteger();
        var repo =
                repository(
                        new Contents() {
                            public byte[] download(String reference, long limit) {
                                downloads.incrementAndGet();
                                entered.countDown();
                                try {
                                    if (!release.await(5, TimeUnit.SECONDS))
                                        throw new AssertionError("Download test timed out");
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(error);
                                }
                                return body(reference);
                            }
                        },
                        50L * 1024 * 1024);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Future<AgentReleaseSnapshot> first = workers.submit(() -> repo.acquire(manifest(1)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<AgentReleaseSnapshot> second = workers.submit(() -> repo.acquire(manifest(1)));
            release.countDown();
            try (var a = first.get(2, TimeUnit.SECONDS);
                    var b = second.get(2, TimeUnit.SECONDS)) {
                assertEquals(1, downloads.get());
                assertThrows(IllegalStateException.class, () -> repo.acquire(manifest(2)));
                assertEquals(
                        "AGENTS R1",
                        new String(a.open("AGENTS.md").readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var replacement = repo.acquire(manifest(2))) {
                assertNotNull(replacement);
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void failedPreparationReleasesItsCapacityReservation() {
        var attempts = new AtomicInteger();
        var repo =
                repository(
                        new Contents() {
                            public byte[] download(String reference, long limit) {
                                if (attempts.incrementAndGet() == 1)
                                    throw new IllegalStateException("synthetic transfer failure");
                                return body(reference);
                            }
                        },
                        50L * 1024 * 1024);
        assertThrows(IllegalStateException.class, () -> repo.acquire(manifest(1)));
        try (var recovered = repo.acquire(manifest(2))) {
            assertNotNull(recovered);
        }
    }

    @Test
    void recentAbandonedStagingCountsAgainstCapacityAndOldStagingIsReclaimed() throws Exception {
        Path staging = root.resolve("a".repeat(64) + ".staging-crash");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("partial.bin"), "partial");
        var repo =
                repository(
                        new Contents() {
                            public byte[] download(String reference, long limit) {
                                return body(reference);
                            }
                        },
                        50L * 1024 * 1024);
        assertThrows(IllegalStateException.class, () -> repo.acquire(manifest(1)));
        Files.setLastModifiedTime(staging, FileTime.from(Instant.now().minusSeconds(601)));
        try (var recovered = repo.acquire(manifest(1))) {
            assertNotNull(recovered);
        }
        assertFalse(Files.exists(staging));
    }
}
