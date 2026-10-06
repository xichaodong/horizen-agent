package dev.horizen.agent.web.bootstrap.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.horizen.agent.adapter.agentscope.artifact.ArtifactTurnInputService;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.artifact.*;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.web.config.*;
import io.agentscope.harness.agent.DistributedStore;
import dev.horizen.agent.adapter.agentscope.workspace.snapshot.NonCachedSandboxStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

class VisionModelRoutingTest {
    @TempDir
    Path workspace;

    @Test
    void uploadedImageOnlyReachesIndependentVisionEndpointAndMainReceivesText() throws Exception {
        var json = JsonUtils.newMapper();
        var mainRequests = new CopyOnWriteArrayList<JsonNode>();
        var visionRequests = new CopyOnWriteArrayList<JsonNode>();
        var mainHeaders = new CopyOnWriteArrayList<String>();
        var visionHeaders = new CopyOnWriteArrayList<String>();
        var main = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var vision = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        main.createContext("/v1/chat/completions", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            mainRequests.add(request);
            mainHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean answered = mainRequests.size() > 1;
            String delta = answered ? "{\"content\":\"The image is a blue chart.\"}"
                    : "{\"tool_calls\":[{\"index\":0,\"id\":\"vision-call\",\"type\":\"function\",\"function\":{\"name\":\"vision_analyze\",\"arguments\":\"{\\\"artifact_id\\\":\\\"image-1\\\",\\\"question\\\":\\\"Describe the image\\\"}\"}}]}";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(chunk("text-main", delta, answered ? "stop" : "tool_calls").getBytes(StandardCharsets.UTF_8));
            }
        });
        vision.createContext("/v1/chat/completions", exchange -> {
            visionRequests.add(json.readTree(exchange.getRequestBody()));
            visionHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(chunk("image-worker", "{\"content\":\"a blue chart\"}", "stop").getBytes(StandardCharsets.UTF_8));
            }
        });
        main.start();
        vision.start();
        try {
            var artifacts = new ImageStore();
            var contents = new ImageContents();
            var inputs = new ArtifactTurnInputService(artifacts, contents);
            var attachments = inputs.resolve("owner", "session", "turn", List.of("image-1"), false, 5, 1024, 2048, 3600);
            assertTrue(attachments.isEmpty(), "The main turn must carry references instead of image blocks");
            assertEquals(0, contents.urlRequests);
            var primary = new AgentProperties("main-test-key", "http://127.0.0.1:" + main.getAddress().getPort() + "/v1", "text-main", AgentProperties.ModelMode.REMOTE, 5, Duration.ofSeconds(20), Duration.ofSeconds(10));
            var visual = new VisionProperties(true, "vision-test-key", "http://127.0.0.1:" + vision.getAddress().getPort() + "/v1", "image-worker");
            var support = new ArtifactSupport(null, null, inputs, null, contents, artifacts);
            try (var runtime = AgentRuntimeFactory.create(RuntimeAssembly.builder()
                    .properties(primary).visionProperties(visual).contextProperties(new ContextProperties())
                    .gatewayProperties(new GatewayProperties("", "", null, Set.of()))
                    .sandboxProperties(new E2bSandboxProperties(false, "", "", "", "", "/tmp/vision-test", null, null, null, null, null))
                    .multimodalProperties(new MultimodalProperties(null, null, null, null))
                    .snapshotProperties(new SandboxSnapshotProperties()).artifactSupport(support)
                    .distributedStore(DistributedStore.builder()
                            .agentStateStore(new NonCachedSandboxStateStore(new InMemoryAgentStateStore())).baseStore(new InMemoryStore()).build())
                    .publishedWorkspace(workspace).build())) {
                var events = runtime.stream(AgentTurnRequest.builder().turnId("turn").ownerKey("owner")
                                .sessionId("session").message("Describe Artifact image-1 using vision_analyze.").attachments(attachments).build())
                        .collectList().block(Duration.ofSeconds(15));
                assertTrue(events.stream().anyMatch(event -> event.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED && event.getText().contains("blue chart")), events.toString());
                assertTrue(events.stream().anyMatch(event -> event.getType() == AgentRuntimeEvent.Type.TOOL_COMPLETED && "vision_analyze".equals(event.getToolName())));
            }
            assertEquals(2, mainRequests.size());
            assertEquals(1, visionRequests.size());
            assertTrue(mainRequests.stream().allMatch(request -> request.path("model").asText().equals("text-main") && !request.path("messages").toString().contains("image_url")));
            assertEquals("image-worker", visionRequests.get(0).path("model").asText());
            assertTrue(visionRequests.get(0).path("messages").toString().contains("image_url"));
            assertTrue(mainRequests.get(1).path("messages").toString().contains("a blue chart"));
            assertEquals(List.of("Bearer vision-test-key"), visionHeaders);
            assertEquals(List.of("Bearer main-test-key", "Bearer main-test-key"), mainHeaders);
            assertEquals(1, contents.urlRequests);
        } finally {
            main.stop(0);
            vision.stop(0);
        }
    }

    private static String chunk(String model, String delta, String finish) {
        return "data: {\"id\":\"response\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"" + model
                + "\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":\"" + finish + "\"}]}\n\ndata: [DONE]\n\n";
    }

    private static class ImageContents implements ArtifactContentStore {
        int urlRequests;

        public ArtifactContent put(ArtifactContentWrite value) {
            throw new UnsupportedOperationException();
        }

        public byte[] get(String ref) {
            throw new AssertionError("The main model must not fetch image bytes");
        }

        public URI createDownloadUrl(String ref, int expires) {
            urlRequests++;
            return URI.create("https://images.example.test/owned.png");
        }

        public void delete(String ref) {
        }
    }

    private static class ImageStore implements ArtifactStore {
        final Artifact image = new Artifact("image-1", "owner", ArtifactKind.FILE, ArtifactState.READY, "image.png", "image/png", "memory:image", 8L, null, null, ArtifactSource.USER, null, null, Instant.EPOCH, Instant.EPOCH, null, 0);

        public Artifact create(Artifact value) {
            throw new UnsupportedOperationException();
        }

        public Optional<Artifact> find(String owner, String id) {
            return "owner".equals(owner) && "image-1".equals(id) ? Optional.of(image) : Optional.empty();
        }

        public Artifact update(Artifact value, long version) {
            throw new UnsupportedOperationException();
        }

        public void addReference(ArtifactReference value) {
        }

        public List<ArtifactReference> listReferences(String owner, String id) {
            return List.of();
        }

        public List<ArtifactReference> listReferencesForSession(String owner, String session) {
            return List.of();
        }
    }
}
