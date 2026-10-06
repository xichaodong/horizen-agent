package dev.horizen.agent.domain.artifact;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class ArtifactIdentifierBoundaryTest {
    private final Instant now = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    void persistedArtifactFieldsAccept191CharactersAndReject192And256() {
        String max = "a".repeat(191);
        assertEquals(max, artifact(max, max, max).getArtifactId());
        assertEquals(
                max,
                new ArtifactContentWrite(max, max, new byte[]{1}, "text/plain").getOwnerKey());
        for (int size : new int[]{192, 256}) {
            String invalid = "a".repeat(size);
            assertThrows(IllegalArgumentException.class, () -> artifact(invalid, "id", null));
            assertThrows(IllegalArgumentException.class, () -> artifact("owner", invalid, null));
            assertThrows(IllegalArgumentException.class, () -> artifact("owner", "id", invalid));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ArtifactContentWrite(invalid, "id", new byte[]{1}, "text/plain"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ArtifactContentWrite("owner", invalid, new byte[]{1}, "text/plain"));
        }
    }

    @Test
    void referenceColumnsAreBoundedButJsonOnlyToolCallIdKeepsItsExistingContract() {
        String max = "a".repeat(191);
        var reference = reference(max, max, max, max, max);
        assertEquals(max, reference.getReferenceId());
        assertEquals(256, reference.getToolCallId().length());
        String invalid = "a".repeat(192);
        assertThrows(
                IllegalArgumentException.class,
                () -> reference(invalid, "owner", "id", "session", "turn"));
        assertThrows(
                IllegalArgumentException.class,
                () -> reference("ref", invalid, "id", "session", "turn"));
        assertThrows(
                IllegalArgumentException.class,
                () -> reference("ref", "owner", invalid, "session", "turn"));
        assertThrows(
                IllegalArgumentException.class,
                () -> reference("ref", "owner", "id", invalid, "turn"));
        assertThrows(
                IllegalArgumentException.class,
                () -> reference("ref", "owner", "id", "session", invalid));
    }

    private Artifact artifact(String owner, String id, String parent) {
        return new Artifact(
                id,
                owner,
                ArtifactKind.FILE,
                ArtifactState.UPLOADING,
                "file",
                "text/plain",
                null,
                null,
                null,
                parent,
                ArtifactSource.USER,
                "source",
                null,
                now,
                now,
                null,
                0);
    }

    private ArtifactReference reference(
            String ref, String owner, String artifact, String session, String turn) {
        return new ArtifactReference(
                ref,
                owner,
                artifact,
                session,
                turn,
                ArtifactReferenceRole.OUTPUT,
                "a".repeat(256),
                now);
    }
}
