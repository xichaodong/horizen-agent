package dev.horizen.agent.application.workspace;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.AgentReleaseRepository;
import dev.horizen.agent.domain.workspace.release.AgentReleaseSnapshot;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceReleaseRepository;
import dev.horizen.agent.skill.SkillReleaseManifest;
import dev.horizen.agent.skill.SkillReleaseSnapshot;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class SessionWorkspaceReleaseTest {
    private final AgentCatalogKey catalog = new AgentCatalogKey(7, "test-agent");
    private final AgentReleaseManifest r1 = manifest(1), r2 = manifest(2);
    private final Map<String, SessionWorkspaceRelease> rows = new ConcurrentHashMap<>();
    private final SessionWorkspaceReleaseRepository bindings =
            new SessionWorkspaceReleaseRepository() {
                public Optional<SessionWorkspaceRelease> find(String owner, String session) {
                    return Optional.ofNullable(rows.get(owner + "/" + session));
                }

                public SessionWorkspaceRelease bindIfAbsent(
                        String owner, String session, SessionWorkspaceRelease release) {
                    return rows.computeIfAbsent(owner + "/" + session, k -> release);
                }
            };

    @Test
    void oldSessionStaysPinnedAcrossNewTurnsRecoveryAndServiceRestart() {
        AtomicReference<AgentReleaseManifest> current = new AtomicReference<>(r1);
        var repo = repository(current);
        var service = new AgentReleaseService(catalog, repo, bindings);
        try (var first = service.beginExecution("owner", "old", false)) {
            assertEquals(1, first.getManifest().getReleaseId());
        }
        current.set(r2);
        try (var old = service.beginExecution("owner", "old", false);
             var fresh = service.beginExecution("owner", "new", false);
             var resumed =
                     new AgentReleaseService(catalog, repository(current), bindings)
                             .beginExecution("owner", "old", true)) {
            assertEquals(1, old.getManifest().getReleaseId());
            assertEquals(2, fresh.getManifest().getReleaseId());
            assertEquals(1, resumed.getManifest().getReleaseId());
        }
    }

    @Test
    void missingOrChangedOldReleaseNeverFallsBackToCurrentAndUnboundResumeCannotBind() {
        rows.put("owner/old", SessionWorkspaceRelease.of(r1));
        AgentReleaseRepository wrong =
                new AgentReleaseRepository() {
                    public Optional<AgentReleaseManifest> findCurrent(AgentCatalogKey key) {
                        return Optional.of(r2);
                    }

                    public Optional<AgentReleaseManifest> findById(AgentCatalogKey key, long id) {
                        return Optional.of(r2);
                    }

                    public AgentReleaseSnapshot acquire(AgentReleaseManifest value) {
                        throw new AssertionError("must reject before executing");
                    }
                };
        var service = new AgentReleaseService(catalog, wrong, bindings);
        assertThrows(SecurityException.class, () -> service.beginExecution("owner", "old", true));
        AgentReleaseRepository missing =
                new AgentReleaseRepository() {
                    public Optional<AgentReleaseManifest> findCurrent(AgentCatalogKey key) {
                        throw new AssertionError("must not consult latest");
                    }

                    public Optional<AgentReleaseManifest> findById(AgentCatalogKey key, long id) {
                        return Optional.empty();
                    }

                    public AgentReleaseSnapshot acquire(AgentReleaseManifest value) {
                        throw new AssertionError("must not execute");
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        new AgentReleaseService(catalog, missing, bindings)
                                .beginExecution("owner", "old", true));
        assertThrows(
                IllegalStateException.class,
                () -> service.beginExecution("owner", "unknown", true));
        assertFalse(rows.containsKey("owner/unknown"));
        rows.put(
                "owner/wrong-scope",
                new SessionWorkspaceRelease(
                        new AgentCatalogKey(8, "test-agent"), 1, r1.getReleaseHash()));
        assertThrows(
                SecurityException.class,
                () -> service.beginExecution("owner", "wrong-scope", false));
    }

    @Test
    void concurrentFirstSelectionReturnsTheWinnerAndClosesLosingPreparation() throws Exception {
        var entered = new CyclicBarrier(2);
        var sequence = new AtomicInteger();
        var closed = new AtomicInteger();
        AgentReleaseRepository repo =
                new AgentReleaseRepository() {
                    public Optional<AgentReleaseManifest> findCurrent(AgentCatalogKey key) {
                        return Optional.of(sequence.incrementAndGet() == 1 ? r1 : r2);
                    }

                    public Optional<AgentReleaseManifest> findById(AgentCatalogKey key, long id) {
                        return Optional.of(id == 1 ? r1 : r2);
                    }

                    public AgentReleaseSnapshot acquire(AgentReleaseManifest value) {
                        if (rows.isEmpty())
                            try {
                                entered.await(3, TimeUnit.SECONDS);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        return snapshot(value, closed);
                    }
                };
        var service = new AgentReleaseService(catalog, repo, bindings);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> service.beginExecution("owner", "same", false));
            var b = pool.submit(() -> service.beginExecution("owner", "same", false));
            try (var first = a.get(5, TimeUnit.SECONDS);
                 var second = b.get(5, TimeUnit.SECONDS)) {
                assertEquals(
                        first.getManifest().getReleaseId(), second.getManifest().getReleaseId());
                assertEquals(1, closed.get());
            }
            assertEquals(3, closed.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private AgentReleaseRepository repository(AtomicReference<AgentReleaseManifest> current) {
        return new AgentReleaseRepository() {
            public Optional<AgentReleaseManifest> findCurrent(AgentCatalogKey key) {
                return Optional.of(current.get());
            }

            public Optional<AgentReleaseManifest> findById(AgentCatalogKey key, long id) {
                return Optional.of(id == 1 ? r1 : r2);
            }

            public AgentReleaseSnapshot acquire(AgentReleaseManifest value) {
                return snapshot(value, new AtomicInteger());
            }
        };
    }

    private AgentReleaseSnapshot snapshot(AgentReleaseManifest manifest, AtomicInteger closed) {
        return new AgentReleaseSnapshot(
                manifest,
                new SkillReleaseSnapshot(manifest.getSkillRelease(), Map.of()),
                p -> new ByteArrayInputStream(new byte[0]),
                closed::incrementAndGet);
    }

    private AgentReleaseManifest manifest(int id) {
        String hash = (id == 1 ? "a" : "b").repeat(64);
        return new AgentReleaseManifest(
                7,
                "test-agent",
                id,
                id,
                hash,
                List.of(
                        new AgentReleaseManifest.Asset(
                                "AGENTS.md",
                                "https://assets.example/agents",
                                hash,
                                1,
                                "text/plain")),
                new SkillReleaseManifest(7, id, id, hash, 0, List.of()));
    }
}
