package dev.horizen.agent.domain.artifact;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.*;

class ArtifactCompensationTest {
    private final Store store = new Store();
    private final Content content = new Content(store);
    private final ArtifactLifecycleService service = new ArtifactLifecycleService(store, content);
    private final Instant now = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    void referenceFailurePreservesReadyContentAndCanRetryWithoutUploadingAgain() {
        store.failReference = true;
        assertThrows(IllegalStateException.class, () -> service.publishFile(request()));
        assertEquals(ArtifactState.READY, store.saved.getState());
        assertTrue(content.values.contains(store.saved.getContentRef()));
        assertEquals(0, content.deletes);
        store.failReference = false;
        var retried = service.publishFile(request());
        assertEquals(ArtifactState.READY, retried.getState());
        assertEquals(1, content.puts);
        assertEquals(1, store.references);
    }

    @Test
    void lostReadyAcknowledgementDoesNotDeleteCommittedContent() {
        store.loseReadyReply = true;
        assertThrows(IllegalStateException.class, () -> service.publishFile(request()));
        assertEquals(ArtifactState.READY, store.saved.getState());
        assertTrue(content.values.contains(store.saved.getContentRef()));
        assertEquals(0, content.deletes);
    }

    @Test
    void confirmedUncommittedUploadIsMarkedFailedBeforeDeletingContent() {
        store.rejectReady = true;
        assertThrows(IllegalStateException.class, () -> service.publishFile(request()));
        assertEquals(ArtifactState.FAILED, store.saved.getState());
        assertEquals(1, content.deletes);
        assertTrue(content.values.isEmpty());
    }

    @Test
    void userUploadAlsoPreservesContentWhenCommitAcknowledgementIsLost() {
        store.loseReadyReply = true;
        assertThrows(
                IllegalStateException.class,
                () -> service.uploadUserFile("owner", "file", "text/plain", new byte[] {1}, now));
        assertEquals(ArtifactState.READY, store.saved.getState());
        assertTrue(content.values.contains(store.saved.getContentRef()));
        assertEquals(0, content.deletes);
    }

    @Test
    void uncertainCompensationKeepsContentAndPreservesOriginalFailure() {
        store.rejectReady = true;
        store.rejectFailed = true;
        var error = assertThrows(IllegalStateException.class, () -> service.publishFile(request()));
        assertEquals("ready write failed", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals(0, content.deletes);
        assertEquals(1, content.values.size());
    }

    private ArtifactPublicationRequest request() {
        return new ArtifactPublicationRequest(
                "owner",
                "session",
                "turn",
                "source",
                "file",
                "text/plain",
                new byte[] {1},
                null,
                null,
                now);
    }

    private static final class Store implements ArtifactStore {
        Artifact saved;
        boolean failReference, loseReadyReply, rejectReady, rejectFailed;
        int references;

        public Artifact create(Artifact value) {
            saved = value;
            return value;
        }

        public Optional<Artifact> find(String owner, String id) {
            return Optional.ofNullable(saved);
        }

        public Artifact update(Artifact value, long expected) {
            if (saved.getVersion() != expected) throw new IllegalStateException("version changed");
            if (!saved.getState().canTransitionTo(value.getState()))
                throw new IllegalStateException("invalid state");
            if (rejectReady && value.getState() == ArtifactState.READY)
                throw new IllegalStateException("ready write failed");
            if (rejectFailed && value.getState() == ArtifactState.FAILED)
                throw new IllegalStateException("compensation failed");
            saved =
                    new Artifact(
                            value.getArtifactId(),
                            value.getOwnerKey(),
                            value.getKind(),
                            value.getState(),
                            value.getTitle(),
                            value.getMediaType(),
                            value.getContentRef(),
                            value.getSizeBytes(),
                            value.getChecksumSha256(),
                            value.getParentArtifactId(),
                            value.getSource(),
                            value.getSourceRef(),
                            value.getExpiresAt(),
                            value.getCreatedAt(),
                            value.getUpdatedAt(),
                            value.getDeletedAt(),
                            expected + 1);
            if (loseReadyReply && value.getState() == ArtifactState.READY)
                throw new IllegalStateException("commit reply lost");
            return saved;
        }

        public void addReference(ArtifactReference value) {
            if (failReference) throw new IllegalStateException("reference write failed");
            references++;
        }

        public List<ArtifactReference> listReferences(String owner, String id) {
            return List.of();
        }
    }

    private static final class Content implements ArtifactContentStore {
        final Set<String> values = new HashSet<>();
        final Store store;

        Content(Store store) {
            this.store = store;
        }

        int puts, deletes;

        public ArtifactContent put(ArtifactContentWrite request) {
            String ref = "content:" + (++puts);
            values.add(ref);
            return new ArtifactContent(ref, 1, "a".repeat(64));
        }

        public byte[] get(String ref) {
            return new byte[] {1};
        }

        public URI createDownloadUrl(String ref, int seconds) {
            return URI.create(ref);
        }

        public void delete(String ref) {
            assertEquals(
                    ArtifactState.FAILED,
                    store.saved.getState(),
                    "Content must be uncommitted before deletion");
            deletes++;
            values.remove(ref);
        }
    }
}
