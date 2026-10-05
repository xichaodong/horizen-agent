package dev.horizen.agent.adapter.agentscope.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactContent;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactContentWrite;
import dev.horizen.agent.domain.artifact.ArtifactDescriptor;
import dev.horizen.agent.domain.artifact.ArtifactEventCollector;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.artifact.ArtifactLineageContext;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class ArtifactDeliveryBridgeTest {
    @Test
    void publishesSandboxFileAsOutputArtifactAndIsIdempotent() {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        InMemoryContentStore contents = new InMemoryContentStore();
        ArtifactDeliveryBridge bridge =
                new ArtifactDeliveryBridge(new ArtifactLifecycleService(artifacts, contents));
        ArtifactEventCollector collector = new ArtifactEventCollector();
        ArtifactLineageContext lineage = new ArtifactLineageContext();
        lineage.recordLoaded("art-input");
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("session")
                        .put(ArtifactExecutionContext.class, new ArtifactExecutionContext("turn"))
                        .put(ArtifactEventCollector.class, collector)
                        .put(ArtifactLineageContext.class, lineage)
                        .build();
        ArtifactDeliveryRequest request =
                new ArtifactDeliveryRequest(
                        "outputs/report.md", "report".getBytes(), "report.md", "Report", false);

        assertTrue(bridge.deliver(context, request).successful());
        assertTrue(bridge.deliver(context, request).successful());

        List<ArtifactDescriptor> published = collector.drain();
        assertEquals(2, published.size());
        assertEquals(published.get(0).getArtifactId(), published.get(1).getArtifactId());
        Artifact artifact = artifacts.find("owner", published.get(0).getArtifactId()).orElseThrow();
        assertEquals(ArtifactState.READY, artifact.getState());
        assertEquals("art-input", artifact.getParentArtifactId());
        assertEquals(1, contents.values.size());
        assertEquals(1, artifacts.references.size());
        assertEquals(ArtifactReferenceRole.OUTPUT, artifacts.references.get(0).getRole());
    }

    private static final class InMemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> values = new HashMap<>();
        private final List<ArtifactReference> references = new ArrayList<>();

        @Override
        public Artifact create(Artifact artifact) {
            values.put(key(artifact.getOwnerKey(), artifact.getArtifactId()), artifact);
            return artifact;
        }

        @Override
        public Optional<Artifact> find(String ownerKey, String artifactId) {
            return Optional.ofNullable(values.get(key(ownerKey, artifactId)));
        }

        @Override
        public Artifact update(Artifact artifact, long expectedVersion) {
            Artifact current = find(artifact.getOwnerKey(), artifact.getArtifactId()).orElseThrow();
            if (current.getVersion() != expectedVersion) {
                throw new IllegalStateException("version changed");
            }
            Artifact next =
                    new Artifact(
                            artifact.getArtifactId(),
                            artifact.getOwnerKey(),
                            artifact.getKind(),
                            artifact.getState(),
                            artifact.getTitle(),
                            artifact.getMediaType(),
                            artifact.getContentRef(),
                            artifact.getSizeBytes(),
                            artifact.getChecksumSha256(),
                            artifact.getParentArtifactId(),
                            artifact.getSource(),
                            artifact.getSourceRef(),
                            artifact.getExpiresAt(),
                            artifact.getCreatedAt(),
                            artifact.getUpdatedAt(),
                            artifact.getDeletedAt(),
                            expectedVersion + 1);
            values.put(key(next.getOwnerKey(), next.getArtifactId()), next);
            return next;
        }

        @Override
        public void addReference(ArtifactReference reference) {
            if (references.stream()
                    .noneMatch(
                            value -> value.getReferenceId().equals(reference.getReferenceId()))) {
                references.add(reference);
            }
        }

        @Override
        public List<ArtifactReference> listReferences(String ownerKey, String artifactId) {
            return references.stream()
                    .filter(
                            value ->
                                    value.getOwnerKey().equals(ownerKey)
                                            && value.getArtifactId().equals(artifactId))
                    .toList();
        }

        private static String key(String owner, String artifact) {
            return owner + '\0' + artifact;
        }
    }

    private static final class InMemoryContentStore implements ArtifactContentStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public ArtifactContent put(ArtifactContentWrite request) {
            String ref = "content/" + request.getArtifactId();
            values.put(ref, request.content());
            return new ArtifactContent(ref, request.content().length, "a".repeat(64));
        }

        @Override
        public byte[] get(String contentRef) {
            return values.get(contentRef).clone();
        }

        @Override
        public URI createDownloadUrl(String contentRef, int expiresInSeconds) {
            return URI.create("https://example.test/" + contentRef);
        }

        @Override
        public void delete(String contentRef) {
            values.remove(contentRef);
        }
    }
}
