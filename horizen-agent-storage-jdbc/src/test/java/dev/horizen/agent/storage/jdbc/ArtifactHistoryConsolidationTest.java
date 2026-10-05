package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactSource;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcArtifactStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcTurnTimelineStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

class ArtifactHistoryConsolidationTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private JdbcArtifactStore artifacts;
    private JdbcSessionTurnStore sessions;
    private final Instant at = Instant.parse("2026-10-03T00:00:00Z");

    @BeforeEach
    void prepare() {
        source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:artifact-history-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        artifacts = new JdbcArtifactStore(source);
        sessions = new JdbcSessionTurnStore(source);
    }

    @Test
    void oneFileCanBeOutputThenInputInMultipleTurnsAndSessionsWithoutDuplicatingFiles() {
        artifacts.create(file("file-a"));
        artifacts.addReference(
                ref("output", "file-a", "session-a", "turn-1", ArtifactReferenceRole.OUTPUT, at));
        start("session-a", "turn-2");
        var input =
                ref(
                        "input-a",
                        "file-a",
                        "session-a",
                        "turn-2",
                        ArtifactReferenceRole.INPUT,
                        at.plusSeconds(1));
        artifacts.addReference(input);
        artifacts.addReference(input);
        start("session-b", "turn-3");
        artifacts.addReference(
                ref(
                        "input-b",
                        "file-a",
                        "session-b",
                        "turn-3",
                        ArtifactReferenceRole.INPUT,
                        at.plusSeconds(2)));
        assertEquals(
                List.of("file-a"),
                new JdbcSessionTurnStore(source)
                        .listFinalMessages("owner", "session-a")
                        .get(0)
                        .getArtifactIds());
        assertEquals(
                List.of("file-a"),
                sessions.listFinalMessages("owner", "session-b").get(0).getArtifactIds());
        assertEquals(3, new JdbcArtifactStore(source).listReferences("owner", "file-a").size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ha_artifact", Integer.class));
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ha_conversation_history WHERE record_type='ARTIFACT_REFERENCE'",
                        Integer.class));
        assertTrue(
                new JdbcTurnTimelineStore(source).listForSession("owner", "session-a").isEmpty());
        assertEquals(1, artifacts.listForSession("owner", "session-a", 1).size());
        assertEquals(1, artifacts.listRecentOutputs("owner", "session-a", 1).size());
        assertTrue(artifacts.listRecentOutputs("owner", "session-b", 1).isEmpty());
        assertTrue(artifacts.listForSession("other", "session-a", 10).isEmpty());
    }

    @Test
    void parallelInputsUpdateMessageMetadataWithoutLostIdsAndChangedReferenceIdsAreRejected()
            throws Exception {
        artifacts.create(file("file-a"));
        artifacts.create(file("file-b"));
        start("session", "turn");
        var a = ref("input-a", "file-a", "session", "turn", ArtifactReferenceRole.INPUT, at);
        var b = ref("input-b", "file-b", "session", "turn", ArtifactReferenceRole.INPUT, at);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var x = pool.submit(() -> artifacts.addReference(a));
            var y = pool.submit(() -> new JdbcArtifactStore(source).addReference(b));
            x.get();
            y.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                Set.of("file-a", "file-b"),
                new HashSet<>(
                        sessions.listFinalMessages("owner", "session").get(0).getArtifactIds()));
        assertThrows(
                IllegalStateException.class,
                () ->
                        artifacts.addReference(
                                ref(
                                        "input-a",
                                        "file-b",
                                        "session",
                                        "turn",
                                        ArtifactReferenceRole.INPUT,
                                        at)));
        assertEquals(2, artifacts.listReferencesForSession("owner", "session").size());
    }

    @Test
    void recentOutputsAreReadyUniqueBoundedAndDoNotIncludeInputs() {
        for (String id : List.of("file-a", "file-b", "file-c")) artifacts.create(file(id));
        artifacts.addReference(
                ref("output-a1", "file-a", "session", "turn-1", ArtifactReferenceRole.OUTPUT, at));
        artifacts.addReference(
                ref(
                        "output-a2",
                        "file-a",
                        "session",
                        "turn-2",
                        ArtifactReferenceRole.OUTPUT,
                        at.plusSeconds(3)));
        artifacts.addReference(
                ref(
                        "output-b",
                        "file-b",
                        "session",
                        "turn-2",
                        ArtifactReferenceRole.OUTPUT,
                        at.plusSeconds(2)));
        artifacts.addReference(
                ref(
                        "input-c",
                        "file-c",
                        "session",
                        "turn-2",
                        ArtifactReferenceRole.INPUT,
                        at.plusSeconds(4)));
        assertEquals(
                List.of("file-a", "file-b"),
                artifacts.listRecentOutputs("owner", "session", 10).stream()
                        .map(Artifact::getArtifactId)
                        .toList());
        assertEquals(
                "file-a",
                artifacts.listRecentOutputs("owner", "session", 1).get(0).getArtifactId());
        jdbc.update(
                "UPDATE ha_artifact SET status='DELETED',deleted_at=? WHERE artifact_id='file-a'",
                Timestamp.from(at));
        assertEquals(
                List.of("file-b"),
                artifacts.listRecentOutputs("owner", "session", 10).stream()
                        .map(Artifact::getArtifactId)
                        .toList());
    }

    private Artifact file(String id) {
        return new Artifact(
                id,
                "owner",
                ArtifactKind.FILE,
                ArtifactState.READY,
                id,
                "text/plain",
                "objects/" + id,
                8L,
                "a".repeat(64),
                null,
                ArtifactSource.USER,
                "upload",
                null,
                at,
                at,
                null,
                0);
    }

    private ArtifactReference ref(
            String id,
            String file,
            String session,
            String turn,
            ArtifactReferenceRole role,
            Instant time) {
        return new ArtifactReference(id, "owner", file, session, turn, role, null, time);
    }

    private void start(String session, String turn) {
        sessions.startTurn(
                new StartTurnCommand(
                        new ExecutionIdentity("owner", "actor"),
                        session,
                        turn,
                        "request",
                        "instance",
                        "user-" + turn,
                        "question",
                        at,
                        at.plusSeconds(60),
                        at.plusSeconds(30)));
    }
}
