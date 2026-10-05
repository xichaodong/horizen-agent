package dev.horizen.agent.storage.jdbc.repository.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactSource;
import dev.horizen.agent.domain.artifact.ArtifactState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

class JdbcArtifactStoreTest {
    private JdbcArtifactStore store;
    private Instant now;

    @BeforeEach
    void setUp() {
        String database = "artifact_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + database
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(dataSource);
        store = new JdbcArtifactStore(dataSource);
        now = Instant.parse("2026-09-25T02:00:00Z");
    }

    @Test
    void keepsArtifactIdentitySeparateFromItsTurnReferences() {
        Artifact source = ready("art-source", "owner-a", null, ArtifactSource.USER, "upload-1");
        Artifact result =
                ready("art-result", "owner-a", "art-source", ArtifactSource.TOOL, "tool-call-2");
        store.create(source);
        store.create(result);

        store.addReference(
                reference("ref-input", "owner-a", "art-source", ArtifactReferenceRole.INPUT, null));
        store.addReference(
                reference(
                        "ref-output",
                        "owner-a",
                        "art-result",
                        ArtifactReferenceRole.OUTPUT,
                        "tool-call-2"));

        Artifact stored = store.find("owner-a", "art-result").orElseThrow();
        assertEquals("art-source", stored.getParentArtifactId());
        assertEquals(ArtifactSource.TOOL, stored.getSource());
        assertEquals(
                ArtifactReferenceRole.OUTPUT,
                store.listReferences("owner-a", "art-result").get(0).getRole());
        assertEquals(2, store.listReferencesForSession("owner-a", "session-1").size());
        assertEquals(
                "art-source",
                store.listReferencesForSession("owner-a", "session-1").get(0).getArtifactId());
    }

    @Test
    void tracksUploadToReadyWithOptimisticVersioning() {
        Artifact uploading =
                new Artifact(
                        "art-upload",
                        "owner-a",
                        ArtifactKind.FILE,
                        ArtifactState.UPLOADING,
                        "Draft report",
                        "application/pdf",
                        null,
                        null,
                        null,
                        null,
                        ArtifactSource.AGENT,
                        null,
                        null,
                        now,
                        now,
                        null,
                        0);
        store.create(uploading);

        Artifact ready =
                new Artifact(
                        "art-upload",
                        "owner-a",
                        ArtifactKind.FILE,
                        ArtifactState.READY,
                        "Draft report",
                        "application/pdf",
                        "objects/art-upload.pdf",
                        12L,
                        "a".repeat(64),
                        null,
                        ArtifactSource.AGENT,
                        null,
                        null,
                        now,
                        now.plusSeconds(1),
                        null,
                        0);
        Artifact updated = store.update(ready, 0);

        assertEquals(ArtifactState.READY, updated.getState());
        assertEquals(1, updated.getVersion());
        assertThrows(IllegalStateException.class, () -> store.update(ready, 0));
    }

    @Test
    void preventsCrossOwnerReferences() {
        store.create(ready("art-private", "owner-a", null, ArtifactSource.USER, null));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.addReference(
                                reference(
                                        "ref-cross-owner",
                                        "owner-b",
                                        "art-private",
                                        ArtifactReferenceRole.INPUT,
                                        null)));
    }

    @Test
    void pagesUniqueArtifactsWithoutRepeatingReferencesOrCrossingOwners() {
        for (int i = 0; i < 12; i++) {
            String id = "page-" + i;
            store.create(ready(id, "owner-a", null, ArtifactSource.USER, null));
            store.addReference(
                    reference("page-ref-" + i, "owner-a", id, ArtifactReferenceRole.INPUT, null));
        }
        store.addReference(
                reference(
                        "page-duplicate", "owner-a", "page-0", ArtifactReferenceRole.INPUT, null));
        var first = store.listForSession("owner-a", "session-1", 10, 0);
        var next = store.listForSession("owner-a", "session-1", 10, 10);
        assertEquals(10, first.size());
        assertEquals(2, next.size());
        assertEquals(
                12,
                Stream.concat(first.stream(), next.stream())
                        .map(Artifact::getArtifactId)
                        .distinct()
                        .count());
        assertEquals(0, store.listForSession("owner-b", "session-1", 10, 0).size());
    }

    private Artifact ready(
            String id, String owner, String parent, ArtifactSource source, String sourceRef) {
        return new Artifact(
                id,
                owner,
                ArtifactKind.FILE,
                ArtifactState.READY,
                "Workbook",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "objects/" + id,
                8L,
                "b".repeat(64),
                parent,
                source,
                sourceRef,
                null,
                now,
                now,
                null,
                0);
    }

    private ArtifactReference reference(
            String id,
            String owner,
            String artifactId,
            ArtifactReferenceRole role,
            String toolCallId) {
        return new ArtifactReference(
                id, owner, artifactId, "session-1", "turn-1", role, toolCallId, now);
    }
}
