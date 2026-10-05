package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;

import dev.horizen.agent.adapter.agentscope.workspace.snapshot.NonCachedSandboxStateStore;
import dev.horizen.agent.adapter.skill.horizen.AgentScopeSkillRepositoryAdapter;
import dev.horizen.agent.application.workspace.AgentReleaseService;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.AgentReleaseRepository;
import dev.horizen.agent.domain.workspace.release.AgentReleaseSnapshot;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceReleaseRepository;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.skill.SkillReleaseManifest;
import dev.horizen.agent.skill.SkillReleaseSnapshot;
import dev.horizen.agent.web.bootstrap.runtime.AgentRuntimeFactory;
import dev.horizen.agent.web.bootstrap.runtime.PublishedAgentRuntime;
import dev.horizen.agent.web.bootstrap.runtime.RuntimeAssembly;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.ContextProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.MultimodalProperties;
import dev.horizen.agent.web.config.SandboxSnapshotProperties;

import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class PublishedAgentRuntimeTest {
    @TempDir Path directory;

    @Test
    void productionFactoryUsesPublishedContextWithoutOldLocalOrSessionDefinitions()
            throws Exception {
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newFixedThreadPool(2);
        model.setExecutor(workers);
        var firstEntered = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        Map<String, String> prompts = new ConcurrentHashMap<>();
        ObjectMapper json = new ObjectMapper();
        model.createContext(
                "/v1/chat/completions",
                e -> {
                    JsonNode request = json.readTree(e.getRequestBody());
                    String mode = "";
                    String system = "";
                    assertTrue(
                            request.path("tools").toString().contains("read_file"),
                            "Base workspace tools must stay visible");
                    for (JsonNode msg : request.path("messages")) {
                        if (msg.path("role").asText().equals("user"))
                            mode = msg.path("content").toString();
                        if (msg.path("role").asText().equals("system"))
                            system += msg.path("content").toString();
                    }
                    prompts.put(mode, system);
                    if (mode.contains("first")) {
                        firstEntered.countDown();
                        try {
                            if (!finishFirst.await(10, TimeUnit.SECONDS))
                                throw new IOException("Test did not release model");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException(error);
                        }
                    }
                    byte[] data =
                            ("data:"
                                            + " {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"verified\"},\"finish_reason\":\"stop\"}]}\n\n"
                                            + "data: [DONE]\n\n")
                                    .getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "text/event-stream");
                    e.sendResponseHeaders(200, data.length);
                    e.getResponseBody().write(data);
                    e.close();
                });
        model.start();
        var states = new InMemoryAgentStateStore();
        var base = new InMemoryStore();
        DistributedStore distributed =
                DistributedStore.builder()
                        .agentStateStore(new NonCachedSandboxStateStore(states))
                        .baseStore(base)
                        .build();
        Map<String, Map<String, byte[]>> contents = new HashMap<>();
        AgentReleaseManifest r1 = release(1, contents);
        AgentReleaseManifest r2 = release(2, contents);
        AtomicReference<AgentReleaseManifest> current = new AtomicReference<>(r1);
        AgentReleaseRepository source =
                new AgentReleaseRepository() {
                    public Optional<AgentReleaseManifest> findCurrent(AgentCatalogKey c) {
                        return Optional.of(current.get());
                    }

                    public Optional<AgentReleaseManifest> findById(AgentCatalogKey c, long id) {
                        return Optional.of(id == 1 ? r1 : r2);
                    }

                    public AgentReleaseSnapshot acquire(AgentReleaseManifest m) {
                        return new AgentReleaseSnapshot(
                                m,
                                new SkillReleaseSnapshot(m.getSkillRelease(), Map.of()),
                                path ->
                                        new ByteArrayInputStream(
                                                contents.get(m.getReleaseHash()).get(path)),
                                () -> {});
                    }
                };
        Map<String, SessionWorkspaceRelease> versions = new ConcurrentHashMap<>();
        SessionWorkspaceReleaseRepository binding =
                new SessionWorkspaceReleaseRepository() {
                    public Optional<SessionWorkspaceRelease> find(String owner, String session) {
                        return Optional.ofNullable(versions.get(owner + session));
                    }

                    public SessionWorkspaceRelease bindIfAbsent(
                            String owner, String session, SessionWorkspaceRelease selected) {
                        return versions.computeIfAbsent(owner + session, k -> selected);
                    }
                };
        var releases =
                new AgentReleaseService(
                        new AgentCatalogKey(7, "horizen-web-agent"), source, binding);
        var properties =
                new AgentProperties(
                        "synthetic-model-key",
                        "http://127.0.0.1:" + model.getAddress().getPort() + "/v1",
                        "test",
                        AgentProperties.ModelMode.REMOTE,
                        3,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(10));
        var context = new ContextProperties();
        var gateway = new GatewayProperties("", "", null, Set.of());
        var sandbox =
                new E2bSandboxProperties(
                        false, "", "", "", "", "/tmp/test-workspace", null, null, null, null, null);
        var multimodal = new MultimodalProperties(null, null, null, null, null);
        List<Path> roots = new CopyOnWriteArrayList<>();
        try (var runtime =
                new PublishedAgentRuntime(
                        releases,
                        request -> Flux.empty(),
                        directory.resolve("executions"),
                        2,
                        (release, root) -> {
                            roots.add(root);
                            return AgentRuntimeFactory.create(
                                    RuntimeAssembly.builder()
                                            .properties(properties)
                                            .contextProperties(context)
                                            .gatewayProperties(gateway)
                                            .sandboxProperties(sandbox)
                                            .multimodalProperties(multimodal)
                                            .traceConfig(null)
                                            .horizenExporter(null)
                                            .skillRepository(new AgentScopeSkillRepositoryAdapter())
                                            .distributedStore(distributed)
                                            .sessionTurns(null)
                                            .artifactSupport(null)
                                            .askUsers(null)
                                            .workspaceDocuments(null)
                                            .snapshots(null)
                                            .snapshotProperties(new SandboxSnapshotProperties())
                                            .snapshotPointers(null)
                                            .publishedWorkspace(root)
                                            .infrastructure(null)
                                            .build());
                        })) {
            var first =
                    runtime.stream(request("turn-1", "session", "first"))
                            .subscribeOn(Schedulers.boundedElastic())
                            .collectList()
                            .toFuture();
            if (!firstEntered.await(5, TimeUnit.SECONDS))
                fail("Model was not called: " + first.get(1, TimeUnit.SECONDS));
            current.set(r2);
            var second =
                    runtime.stream(request("turn-2", "other-session", "second"))
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertTrue(
                    second.stream()
                            .anyMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED));
            finishFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            var third =
                    runtime.stream(request("turn-3", "session", "third"))
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertTrue(
                    third.stream()
                            .anyMatch(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED));
            String initial = prompt(prompts, "first");
            String latest = prompt(prompts, "third");
            assertTrue(initial.contains("AGENTS-R1"), initial);
            assertTrue(initial.contains("worker-R1"), initial);
            assertTrue(latest.contains("AGENTS-R1"), latest);
            assertTrue(latest.contains("worker-R1"), latest);
            assertFalse(latest.contains("AGENTS-R2"), latest);
            assertTrue(prompt(prompts, "second").contains("AGENTS-R2"));
            assertTrue(
                    roots.stream().noneMatch(Files::exists),
                    "Per-execution workspaces must be cleaned");
        } finally {
            finishFirst.countDown();
            model.stop(0);
            workers.shutdownNow();
        }
    }

    private static String prompt(Map<String, String> prompts, String word) {
        return prompts.entrySet().stream()
                .filter(e -> e.getKey().contains(word))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private static AgentTurnRequest request(String turn, String session, String message) {
        return AgentTurnRequest.builder()
                .turnId(turn)
                .ownerKey("synthetic-owner")
                .sessionId(session)
                .message(message)
                .build();
    }

    private static AgentReleaseManifest release(
            int version, Map<String, Map<String, byte[]>> contents) {
        String hash = (version == 1 ? "a" : "b").repeat(64);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("AGENTS.md", ("AGENTS-R" + version).getBytes(StandardCharsets.UTF_8));
        if (version == 1)
            files.put(
                    "subagents/worker.md",
                    "---\ndescription: worker-R1\nworkspace:\n  mode: shared\nsteps: 2\n---\nworker body"
                            .getBytes(StandardCharsets.UTF_8));
        List<AgentReleaseManifest.Asset> assets =
                files.entrySet().stream()
                        .map(
                                e ->
                                        new AgentReleaseManifest.Asset(
                                                e.getKey(),
                                                "https://assets.example/" + hash,
                                                hash,
                                                e.getValue().length,
                                                "text/plain"))
                        .toList();
        contents.put(hash, files);
        return new AgentReleaseManifest(
                7,
                "horizen-web-agent",
                version,
                version,
                hash,
                assets,
                new SkillReleaseManifest(7, version, version, hash, 0, List.of()));
    }
}
