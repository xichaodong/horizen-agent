package dev.horizen.agent.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.execution.turn.*;
import dev.horizen.agent.identity.ExecutionIdentity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class StoredIdentityBoundaryTest {
    @Test
    void identityMatchesThe191CharacterColumnsIncludingUnicodeCharacterCounts() {
        String max = "a".repeat(191);
        assertEquals(max, new ExecutionIdentity(max, max).getOwnerKey());
        String supplementary = "\uD83D\uDE00".repeat(191);
        assertEquals(supplementary, new ExecutionIdentity(supplementary, "actor").getOwnerKey());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionIdentity("a".repeat(192), "actor"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionIdentity("owner", "a".repeat(192)));
    }

    @Test
    void directStartAndTransitionCommandsCannotBypassHttpLimitsAndOverflowStorage() {
        var identity = new ExecutionIdentity("owner", "actor");
        var now = Instant.parse("2026-10-05T00:00:00Z");
        String max = "a".repeat(191);
        assertEquals(
                max,
                new StartTurnCommand(
                        identity,
                        max,
                        max,
                        max,
                        max,
                        max,
                        "hello",
                        now,
                        now.plusSeconds(30),
                        now.plusSeconds(10))
                        .getSessionId());
        String tooLong = "a".repeat(192);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StartTurnCommand(
                                identity,
                                tooLong,
                                "turn",
                                "request",
                                "instance",
                                "message",
                                "hello",
                                now,
                                now.plusSeconds(30),
                                now.plusSeconds(10)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StartTurnCommand(
                                identity,
                                "session",
                                tooLong,
                                "request",
                                "instance",
                                "message",
                                "hello",
                                now,
                                now.plusSeconds(30),
                                now.plusSeconds(10)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TransitionTurnCommand(
                                "owner",
                                "session",
                                tooLong,
                                TurnStatus.CANCELLED,
                                null,
                                null,
                                now,
                                null,
                                null,
                                null));
    }
}
