package dev.horizen.agent.adapter.agentscope.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactContent;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactContentWrite;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactSource;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class ArtifactTurnInputServiceTest {
    @Test
    void referenceOnlyImagesStillRespectCountAndByteLimitsWithoutCreatingUrls() {
        MemoryArtifacts artifacts = new MemoryArtifacts();
        artifacts.add(artifact("owner", "image-a", "image/png", "image-a-ref", 8L));
        artifacts.add(artifact("owner", "image-b", "image/png", "image-b-ref", 8L));
        var service = new ArtifactTurnInputService(artifacts, new MemoryContents(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolve("owner", "session", "turn", List.of("image-a", "image-b"), false, 1, 1024, 2048, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolve("owner", "session", "turn", List.of("image-a"), false, 5, 4, 2048, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolve("owner", "session", "turn", List.of("image-a", "image-b"), false, 5, 1024, 12, 3600));
        assertTrue(artifacts.references.isEmpty());
        assertTrue(service.resolve("owner", "session", "turn", List.of("image-a"), false, 5, 1024, 2048, 3600).isEmpty());
        assertEquals(1, artifacts.references.size());
    }

    @Test
    void resolvesOwnedImagesAndRecordsAllInputReferences() {
        MemoryArtifacts artifacts = new MemoryArtifacts();
        artifacts.add(artifact("owner", "image", "image/png", "image-ref", 8L));
        artifacts.add(artifact("owner", "sheet", "text/csv", "sheet-ref", 3L));
        MemoryContents contents =
                new MemoryContents(
                        Map.of(
                                "image-ref",
                                new byte[]{
                                        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                                },
                                "sheet-ref", new byte[]{1, 2, 3}));

        var inputs =
                new ArtifactTurnInputService(artifacts, contents)
                        .resolve(
                                "owner",
                                "session",
                                "turn",
                                List.of("image", "sheet"),
                                true,
                                5,
                                1024,
                                2048,
                                3600);

        assertEquals(1, inputs.size());
        assertEquals("image", inputs.get(0).getArtifactId());
        assertEquals("https://bos.example.test/image-ref?expires=3600", inputs.get(0).getUrl());
        assertEquals(2, artifacts.references.size());
        assertTrue(
                artifacts.references.stream()
                        .allMatch(reference -> reference.getRole() == ArtifactReferenceRole.INPUT));
    }

    @Test
    void rejectsCrossOwnerUnsupportedMimeAndOversizedImages() {
        MemoryArtifacts artifacts = new MemoryArtifacts();
        artifacts.add(artifact("owner", "bad", "image/svg+xml", "bad-ref", 3L));
        artifacts.add(artifact("owner", "large", "image/png", "large-ref", 3L));
        MemoryContents contents = new MemoryContents(Map.of());
        ArtifactTurnInputService service = new ArtifactTurnInputService(artifacts, contents);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.resolve(
                                "other",
                                "session",
                                "turn",
                                List.of("bad"),
                                true,
                                5,
                                1024,
                                2048,
                                3600));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.resolve(
                                "owner",
                                "session",
                                "turn",
                                List.of("bad"),
                                true,
                                5,
                                1024,
                                2048,
                                3600));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.resolve(
                                "owner",
                                "session",
                                "turn",
                                List.of("large"),
                                true,
                                5,
                                2,
                                2048,
                                3600));
    }

    @Test
    void disabledDirectInputKeepsReferenceWithoutLoadingBytes() {
        MemoryArtifacts artifacts = new MemoryArtifacts();
        artifacts.add(artifact("owner", "image", "image/png", "missing-content", 8L));
        var result =
                new ArtifactTurnInputService(artifacts, new MemoryContents(Map.of()))
                        .resolve(
                                "owner",
                                "session",
                                "turn",
                                List.of("image"),
                                false,
                                5,
                                1024,
                                2048,
                                3600);
        assertTrue(result.isEmpty());
        assertEquals(1, artifacts.references.size());
    }

    private static Artifact artifact(
            String owner, String id, String mediaType, String ref, long size) {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        return new Artifact(
                id,
                owner,
                ArtifactKind.FILE,
                ArtifactState.READY,
                id,
                mediaType,
                ref,
                size,
                null,
                null,
                ArtifactSource.USER,
                null,
                null,
                now,
                now,
                null,
                0);
    }

    private static final class MemoryArtifacts implements ArtifactStore {
        private final Map<String, Artifact> values = new HashMap<>();
        private final List<ArtifactReference> references = new ArrayList<>();

        void add(Artifact artifact) {
            values.put(artifact.getOwnerKey() + "\0" + artifact.getArtifactId(), artifact);
        }

        @Override
        public Artifact create(Artifact artifact) {
            add(artifact);
            return artifact;
        }

        @Override
        public Optional<Artifact> find(String owner, String id) {
            return Optional.ofNullable(values.get(owner + "\0" + id));
        }

        @Override
        public Artifact update(Artifact artifact, long version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addReference(ArtifactReference reference) {
            references.add(reference);
        }

        @Override
        public List<ArtifactReference> listReferences(String owner, String id) {
            return references.stream()
                    .filter(
                            value ->
                                    value.getOwnerKey().equals(owner)
                                            && value.getArtifactId().equals(id))
                    .toList();
        }
    }

    private static final class MemoryContents implements ArtifactContentStore {
        private final Map<String, byte[]> values;

        private MemoryContents(Map<String, byte[]> values) {
            this.values = values;
        }

        @Override
        public ArtifactContent put(ArtifactContentWrite request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] get(String ref) {
            byte[] value = values.get(ref);
            if (value == null) throw new IllegalStateException("content not found");
            return value;
        }

        @Override
        public URI createDownloadUrl(String ref, int expires) {
            return URI.create("https://bos.example.test/" + ref + "?expires=" + expires);
        }

        @Override
        public void delete(String ref) {
        }
    }
}
