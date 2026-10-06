package dev.horizen.agent.adapter.agentscope.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class OwnerScopedLocalArtifactTargetTest {
    @TempDir
    Path directory;

    @Test
    void separatesOwnersEvenWhenSessionAndFileNamesMatch() throws Exception {
        OwnerScopedLocalArtifactTarget target = new OwnerScopedLocalArtifactTarget(directory, 1024);
        var requestA = request("alice");
        var requestB = request("bob");

        assertTrue(target.deliver(context("owner-a"), requestA).successful());
        assertTrue(target.deliver(context("owner-b"), requestB).successful());

        assertArrayEquals(
                bytes("alice"),
                Files.readAllBytes(target.resolve("owner-a", "same-session", "report.json")));
        assertArrayEquals(
                bytes("bob"),
                Files.readAllBytes(target.resolve("owner-b", "same-session", "report.json")));
    }

    @Test
    void rejectsConflictUnlessForceIsExplicit() throws Exception {
        OwnerScopedLocalArtifactTarget target = new OwnerScopedLocalArtifactTarget(directory, 1024);
        assertTrue(target.deliver(context("owner"), request("first")).successful());

        assertTrue(target.deliver(context("owner"), request("second")).conflict());
        var forced =
                new ArtifactDeliveryRequest(
                        "outputs/report.json", bytes("second"), "report.json", null, true);
        assertTrue(target.deliver(context("owner"), forced).successful());
        assertArrayEquals(
                bytes("second"),
                Files.readAllBytes(target.resolve("owner", "same-session", "report.json")));
    }

    @Test
    void rejectsMissingOwnerContextAndOversizedContent() {
        OwnerScopedLocalArtifactTarget target = new OwnerScopedLocalArtifactTarget(directory, 3);

        assertFalse(
                target.deliver(RuntimeContext.builder().sessionId("session").build(), request("ok"))
                        .successful());
        assertFalse(target.deliver(context("owner"), request("toolarge")).successful());
        assertFalse(
                target.deliver(
                                context("owner"),
                                new ArtifactDeliveryRequest(
                                        "output", bytes("ok"), "../escape", null, false))
                        .successful());
    }

    private static RuntimeContext context(String owner) {
        return RuntimeContext.builder().userId(owner).sessionId("same-session").build();
    }

    private static ArtifactDeliveryRequest request(String content) {
        return new ArtifactDeliveryRequest(
                "outputs/report.json", bytes(content), "report.json", "test", false);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
