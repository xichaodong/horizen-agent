package dev.horizen.agent.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.message.TextBlock;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class EvaluationFixtureTest {
    @Test
    void liveCallLimitsDoNotApplyToUnrestrictedToolsAndPreventRepeatedRealCalls() {
        var fixture = new EvaluationFixture(Map.of("modeLabel", "RECORD"));
        fixture.liveLimits(Map.of("update", 1, "publish", 0));
        assertTrue(fixture.allowLiveCall("update"));
        assertFalse(fixture.allowLiveCall("update"));
        assertFalse(fixture.allowLiveCall("publish"));
        assertTrue(fixture.allowLiveCall("query"));
        assertTrue(fixture.allowLiveCall("query"));
    }

    @Test
    void faultsAreConsumedBeforeReplayAndRecoverAfterConfiguredAttempts() {
        var fixture =
                new EvaluationFixture(
                        Map.of("modeLabel", "REPLAY"),
                        List.of(Map.of("tool", "query", "times", 2, "message", "offline")));
        assertNotNull(fixture.fault("query"));
        assertNotNull(fixture.fault("query"));
        assertNull(fixture.fault("query"));
        assertNull(fixture.getFailure());
    }

    @Test
    void repeatedCallsConsumeOrderedEntriesWithNumericAndMapNormalization() {
        var fixture =
                new EvaluationFixture(
                        Map.of(
                                "modeLabel",
                                "REPLAY",
                                "entries",
                                List.of(
                                        Map.of(
                                                "tool",
                                                "query",
                                                "args",
                                                Map.of("id", 1),
                                                "result",
                                                Map.of("value", "first")),
                                        Map.of(
                                                "tool",
                                                "query",
                                                "args",
                                                Map.of("id", 1),
                                                "result",
                                                Map.of("value", "second")))));
        assertTrue(
                ((TextBlock) fixture.replay("query", Map.of("id", 1L)).getOutput().get(0))
                        .getText()
                        .contains("first"));
        assertTrue(
                ((TextBlock) fixture.replay("query", Map.of("id", 1)).getOutput().get(0))
                        .getText()
                        .contains("second"));
        fixture.replay("query", Map.of("id", 1));
        assertEquals("FIXTURE_MISS", fixture.getFailure());
    }

    @Test
    void explicitlyReusableReadsDoNotConsumeDataButWritesRemainSingleUse() {
        var entry =
                Map.of(
                        "tool",
                        "resource",
                        "args",
                        Map.of("id", 1),
                        "result",
                        Map.of("value", "fixed"),
                        "reusable",
                        true);
        var reads = new EvaluationFixture(Map.of("modeLabel", "REPLAY", "entries", List.of(entry)));
        for (int i = 0; i < 3; i++) assertNotNull(reads.replay("resource", Map.of("id", 1), true));
        assertNull(reads.getFailure());
        var writes =
                new EvaluationFixture(Map.of("modeLabel", "REPLAY", "entries", List.of(entry)));
        writes.replay("resource", Map.of("id", 1), false);
        writes.replay("resource", Map.of("id", 1), false);
        assertEquals("FIXTURE_MISS", writes.getFailure());
    }

    @Test
    void relaxedReadFixturesAreExplicitScopedAndCannotMatchWriteContracts() {
        var broad =
                Map.of(
                        "tool",
                        "search_docs",
                        "args",
                        Map.of("tenant", "test", "query", "initial"),
                        "result",
                        "corpus",
                        "reusable",
                        true,
                        "ignoredArgs",
                        List.of("query"));
        var exact =
                Map.of(
                        "tool",
                        "search_docs",
                        "args",
                        Map.of("tenant", "test", "query", "exact"),
                        "result",
                        "exact result",
                        "reusable",
                        true);
        var fixture =
                new EvaluationFixture(
                        Map.of("modeLabel", "REPLAY", "entries", List.of(broad, exact)));
        assertTrue(
                ((TextBlock)
                                fixture.replay(
                                                "search_docs",
                                                Map.of("tenant", "test", "query", "exact"),
                                                true)
                                        .getOutput()
                                        .get(0))
                        .getText()
                        .contains("exact result"));
        assertTrue(
                ((TextBlock)
                                fixture.replay(
                                                "search_docs",
                                                Map.of("tenant", "test", "query", "other"),
                                                true)
                                        .getOutput()
                                        .get(0))
                        .getText()
                        .contains("corpus"));
        fixture.replay("search_docs", Map.of("tenant", "another", "query", "other"), true);
        assertEquals("FIXTURE_MISS", fixture.getFailure());
        var write = new EvaluationFixture(Map.of("modeLabel", "REPLAY", "entries", List.of(broad)));
        write.replay("search_docs", Map.of("tenant", "test", "query", "other"), false);
        assertEquals("FIXTURE_MISS", write.getFailure());
    }

    @Test
    void declaredDefaultsOnlyFillMissingReadArgumentsAndNeverChangeExplicitValues() {
        var entry =
                Map.of(
                        "tool",
                        "list_resources",
                        "args",
                        Map.of("entity", 1),
                        "result",
                        "fixed",
                        "reusable",
                        true,
                        "argumentDefaults",
                        Map.of("page", 1, "limit", 20));
        var fixture =
                new EvaluationFixture(Map.of("modeLabel", "REPLAY", "entries", List.of(entry)));
        fixture.replay("list_resources", Map.of("entity", 1, "page", 1, "limit", 20), true);
        assertNull(fixture.getFailure());
        fixture.replay("list_resources", Map.of("entity", 1, "page", 2, "limit", 20), true);
        assertEquals("FIXTURE_MISS", fixture.getFailure());
        var write = new EvaluationFixture(Map.of("modeLabel", "REPLAY", "entries", List.of(entry)));
        write.replay("list_resources", Map.of("entity", 1, "page", 1, "limit", 20), false);
        assertEquals("FIXTURE_MISS", write.getFailure());
    }
}
