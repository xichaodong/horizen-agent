package dev.horizen.agent.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.execution.session.AgentSession;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.SessionStatus;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.interaction.approval.ApprovalDecisionCommand;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.skill.SkillReleaseManifest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

class ValidatedSnapshotTest {
    private final Instant now = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    void approvalPresentationCreatesIndependentSnapshotAndRoundTrips() throws Exception {
        var initial = approval();
        var changed = initial.withPresentationJson("{\"title\":\"Confirm\"}");
        assertNotSame(initial, changed);
        assertNull(initial.getPresentationJson());
        assertEquals("{\"title\":\"Confirm\"}", changed.getPresentationJson());
        assertEquals(changed, JsonUtils.read(JsonUtils.write(changed), ApprovalRequest.class));
        assertThrows(
                Exception.class,
                () ->
                        JsonUtils.read(
                                JsonUtils.write(changed).replace("\"version\":0", "\"version\":-1"),
                                ApprovalRequest.class));
    }

    @Test
    void optimisticVersionIsCopiedAndValidatedAtJsonBoundary() throws Exception {
        var initial =
                new TransitionTurnCommand(
                        "owner",
                        "session",
                        "turn",
                        TurnStatus.CANCELLED,
                        null,
                        null,
                        now,
                        null,
                        null,
                        null);
        var checked = initial.expectVersion(2);
        assertNull(initial.getExpectedVersion());
        assertEquals(2L, checked.getExpectedVersion());
        assertEquals(
                checked, JsonUtils.read(JsonUtils.write(checked), TransitionTurnCommand.class));
        assertThrows(IllegalArgumentException.class, () -> initial.expectVersion(-1));
        assertThrows(
                Exception.class,
                () ->
                        JsonUtils.read(
                                JsonUtils.write(checked)
                                        .replace("\"expectedVersion\":2", "\"expectedVersion\":-1"),
                                TransitionTurnCommand.class));
    }

    @Test
    void validatedIdentityStatusAndVersionsHaveNoUncheckedMutationEntrypoints() {
        for (Class<?> type :
                List.of(
                        ApprovalRequest.class,
                        ApprovalDecisionCommand.class,
                        StartTurnCommand.class,
                        TransitionTurnCommand.class,
                        ArtifactReference.class,
                        SkillReleaseManifest.class,
                        SkillReleaseManifest.Item.class,
                        ConversationMessage.class)) {
            var setters =
                    Arrays.stream(type.getMethods())
                            .map(method -> method.getName())
                            .filter(
                                    name ->
                                            name.startsWith("set")
                                                    && !name.equals("setArtifactIds"))
                            .toList();
            assertTrue(
                    setters.isEmpty(),
                    () -> type.getName() + " exposes unchecked setters: " + setters);
        }
    }

    @Test
    void immutableReleaseCollectionsRetainJsonCompatibility() throws Exception {
        var release = new SkillReleaseManifest(1, 2, 3, "a".repeat(64), 0, List.of());
        assertThrows(UnsupportedOperationException.class, () -> release.getItems().add(null));
        assertEquals(release, JsonUtils.read(JsonUtils.write(release), SkillReleaseManifest.class));
    }

    @Test
    void sessionIdentityRemainsStableWhenSnapshotOrVersionChanges() {
        var first =
                new AgentSession(
                        "owner",
                        "session",
                        SessionStatus.ACTIVE,
                        null,
                        "actor",
                        "title",
                        false,
                        now,
                        now,
                        now,
                        0);
        var values = new HashSet<AgentSession>();
        values.add(first);
        first.setSnapshotId("snapshot-1");
        assertTrue(values.contains(first));
        var newer =
                new AgentSession(
                        "owner",
                        "session",
                        SessionStatus.ACTIVE,
                        null,
                        "actor",
                        "new title",
                        true,
                        now,
                        now,
                        now,
                        2);
        assertEquals(first, newer);
        var anotherOwner =
                new AgentSession(
                        "other-owner",
                        "session",
                        SessionStatus.ACTIVE,
                        null,
                        "actor",
                        "title",
                        false,
                        now,
                        now,
                        now,
                        0);
        assertNotEquals(first, anotherOwner);
    }

    private ApprovalRequest approval() {
        return new ApprovalRequest(
                "owner",
                "session",
                "turn",
                "approval",
                "reply",
                "call",
                "tool",
                "content",
                "{}",
                ApprovalStatus.PENDING,
                "actor",
                now.plusSeconds(30),
                null,
                null,
                now,
                now,
                0);
    }
}
