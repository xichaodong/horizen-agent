package dev.horizen.agent.web.artifact;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.adapter.agentscope.artifact.ArtifactInputService;
import dev.horizen.agent.adapter.agentscope.artifact.ArtifactTurnInputService;
import dev.horizen.agent.application.session.ConversationHistoryQueryService;
import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactContent;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactContentWrite;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactLineageContext;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactSource;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcArtifactStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcPresentationStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcTurnTimelineStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.time.Instant;
import java.util.*;

class ArtifactHistoryRecoveryTest {
    @Test
    void selectedAttachmentsAndToolLoadedFilesRecoverFromHistoryAfterServiceRecreation() {
        var source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:artifact-recovery-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        var artifacts = new JdbcArtifactStore(source);
        var sessions = new JdbcSessionTurnStore(source);
        Instant at = Instant.now();
        artifacts.create(
                new Artifact(
                        "file-a",
                        "owner",
                        ArtifactKind.FILE,
                        ArtifactState.READY,
                        "report.csv",
                        "text/plain",
                        "objects/a",
                        3L,
                        "a".repeat(64),
                        null,
                        ArtifactSource.TOOL,
                        "call",
                        null,
                        at,
                        at,
                        null,
                        0));
        artifacts.addReference(
                new ArtifactReference(
                        "output-a",
                        "owner",
                        "file-a",
                        "old-session",
                        "old-turn",
                        ArtifactReferenceRole.OUTPUT,
                        "call",
                        at));
        sessions.startTurn(
                new StartTurnCommand(
                        new ExecutionIdentity("owner", "actor"),
                        "session",
                        "turn",
                        "request",
                        "instance",
                        "question",
                        "continue",
                        at,
                        at.plusSeconds(60),
                        at.plusSeconds(30)));
        ArtifactContentStore content =
                new ArtifactContentStore() {
                    public ArtifactContent put(ArtifactContentWrite write) {
                        throw new UnsupportedOperationException();
                    }

                    public byte[] get(String ref) {
                        return new byte[]{1, 2, 3};
                    }

                    public URI createDownloadUrl(String ref, int seconds) {
                        return URI.create("https://assets.example/test");
                    }

                    public void delete(String ref) {
                    }
                };
        new ArtifactTurnInputService(artifacts, content)
                .resolve("owner", "session", "turn", List.of("file-a"), false, 1, 1024, 1024, 60);
        var fs = new RemoteFilesystem(new InMemoryStore(), List.of("synthetic-files"));
        var context =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("session")
                        .put(ArtifactExecutionContext.class, new ArtifactExecutionContext("turn"))
                        .put(AbstractFilesystem.class, fs)
                        .put(ArtifactLineageContext.class, new ArtifactLineageContext())
                        .build();
        new ArtifactInputService(artifacts, content)
                .materialize(context, "file-a", "inputs/report.csv");
        assertEquals(
                List.of("file-a"),
                new JdbcSessionTurnStore(source)
                        .listFinalMessages("owner", "session")
                        .get(0)
                        .getArtifactIds());
        assertEquals(1, artifacts.listReferencesForSession("owner", "session").size());
        var query =
                new ConversationHistoryQueryService(
                        new JdbcSessionTurnStore(source),
                        new JdbcPresentationStore(source),
                        new JdbcTurnTimelineStore(source),
                        new JdbcArtifactStore(source));
        assertEquals(
                "file-a",
                query.load("owner", "session")
                        .getInputArtifactsByTurn()
                        .get("turn")
                        .get(0)
                        .getArtifactId());
        assertTrue(query.load("other", "session").getInputArtifactsByTurn().isEmpty());
        assertTrue(query.load("owner", "session").getTimeline().isEmpty());
        assertEquals(
                "file-a",
                new JdbcArtifactStore(source)
                        .listRecentOutputs("owner", "old-session", 10)
                        .get(0)
                        .getArtifactId());
    }
}
