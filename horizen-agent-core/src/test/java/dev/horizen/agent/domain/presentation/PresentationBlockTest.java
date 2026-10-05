package dev.horizen.agent.domain.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.horizen.agent.domain.artifact.ArtifactDescriptor;
import dev.horizen.agent.domain.artifact.ArtifactKind;

import org.junit.jupiter.api.Test;

class PresentationBlockTest {
    @Test
    void artifactUsesTheSamePresentationProtocolAsOtherCards() {
        ArtifactDescriptor artifact =
                new ArtifactDescriptor(
                        "art-1", ArtifactKind.FILE, "经营诊断.md", "text/markdown", 128L, null);

        PresentationBlock first = PresentationBlock.artifactCard(artifact, 0);
        PresentationBlock replay = PresentationBlock.artifactCard(artifact, 0);

        assertEquals("artifact_card", first.getType());
        assertEquals("report", first.getData().get("variant"));
        assertEquals("art-1", first.getData().get("artifactId"));
        assertEquals(first.getBlockId(), replay.getBlockId());
    }
}
