package dev.horizen.agent.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import io.agentscope.core.message.ToolResultBlock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class EvaluationRunServiceTest {
    private final ExecutionIdentity identity = new ExecutionIdentity("test-owner", "test-actor");

    private static AgentRuntimeEvent event(AgentRuntimeEvent.Type type, String text) {
        return new AgentRuntimeEvent(
                type, "turn", "session", "id", "title", text, "success", null, null, 1L, 1L);
    }

    private static EvaluationProtocol.Start request(String id, Map<String, Object> layer) {
        var request = new EvaluationProtocol.Start();
        request.setExecutionId(id);
        request.setCaseRunId(1L);
        request.setInput(Map.of("case", layer));
        return request;
    }

    @Test
    void multiTurnReturnsEvidenceOnceAndKeepsNoResultAfterCompletionOrRestart() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        calls.incrementAndGet();
                        events.accept(
                                event(AgentRuntimeEvent.Type.TURN_COMPLETED, step.getUserInput()));
                    }
                };
        var request =
                request(
                        "multi",
                        Map.of(
                                "steps",
                                List.of(
                                        Map.of("userInput", "first"),
                                        Map.of("userInput", "second"))));
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var result = service.start(identity, request).get(5, TimeUnit.SECONDS);
            assertEquals("COMPLETED", result.getStatus());
            assertEquals("second", result.getActualOutput().get("lastAgentMessage"));
            assertEquals(4, result.getEvents().size());
            assertEquals(2, calls.get());
            assertThrows(NoSuchElementException.class, () -> service.status(identity, "multi"));
        }
        try (var restarted = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            assertThrows(NoSuchElementException.class, () -> restarted.status(identity, "multi"));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void onlyInFlightStateIsQueryableAndIsolatedByOwner() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        started.countDown();
                        try {
                            finish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                        events.accept(event(AgentRuntimeEvent.Type.TURN_COMPLETED, "done"));
                    }
                };
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var request = request("running", Map.of("steps", List.of(Map.of("userInput", "wait"))));
            var completion = service.start(identity, request);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("RUNNING", service.status(identity, "running").getStatus());
            assertThrows(IllegalArgumentException.class, () -> service.start(identity, request));
            assertThrows(
                    NoSuchElementException.class,
                    () -> service.status(new ExecutionIdentity("other", "actor"), "running"));
            finish.countDown();
            assertEquals("COMPLETED", completion.get(5, TimeUnit.SECONDS).getStatus());
            assertThrows(NoSuchElementException.class, () -> service.status(identity, "running"));
        }
    }

    @Test
    void approvalUsesExplicitScriptAndUnscriptedApprovalReturnsForReview() throws Exception {
        AtomicInteger resumes = new AtomicInteger();
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        events.accept(event(AgentRuntimeEvent.Type.APPROVAL_REQUIRED, null));
                    }

                    public void resume(
                            ExecutionIdentity identity,
                            String session,
                            AgentRuntimeEvent pending,
                            EvaluationProtocol.Interaction script,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        assertEquals("approval", script.getType());
                        assertFalse(script.getApproved());
                        resumes.incrementAndGet();
                        events.accept(event(AgentRuntimeEvent.Type.TURN_COMPLETED, "declined"));
                    }
                };
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var completion =
                    service.start(
                            identity,
                            request(
                                    "decline",
                                    Map.of(
                                            "steps",
                                            List.of(Map.of("userInput", "action")),
                                            "interactions",
                                            List.of(
                                                    Map.of(
                                                            "type",
                                                            "approval",
                                                            "approved",
                                                            false,
                                                            "toolNames",
                                                            List.of("action"))))));
            assertEquals("COMPLETED", completion.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(1, resumes.get());
            completion =
                    service.start(
                            identity,
                            request(
                                    "missing",
                                    Map.of("steps", List.of(Map.of("userInput", "action")))));
            var pending = completion.get(5, TimeUnit.SECONDS);
            assertEquals("INTERACTION_REQUIRED", pending.getStatus());
            assertNull(pending.getErrorCode());
            assertEquals(1, resumes.get());
        }
    }

    @Test
    void optionalQuestionCanBeAbsentButCannotAuthorizeAnUnscriptedApproval() throws Exception {
        AtomicInteger resumes = new AtomicInteger();
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        events.accept(event(AgentRuntimeEvent.Type.APPROVAL_REQUIRED, null));
                    }

                    public void resume(
                            ExecutionIdentity identity,
                            String session,
                            AgentRuntimeEvent pending,
                            EvaluationProtocol.Interaction script,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        assertEquals("approval", script.getType());
                        assertEquals(List.of("action"), script.getToolNames());
                        assertTrue(script.getApproved());
                        resumes.incrementAndGet();
                        events.accept(event(AgentRuntimeEvent.Type.TURN_COMPLETED, "done"));
                    }
                };
        var question =
                Map.of(
                        "type",
                        "ask_user",
                        "optional",
                        true,
                        "selectedOptionLabels",
                        List.of("Confirm"));
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var missing =
                    service.start(
                                    identity,
                                    request(
                                            "optional-missing",
                                            Map.of(
                                                    "steps",
                                                    List.of(Map.of("userInput", "action")),
                                                    "interactions",
                                                    List.of(question))))
                            .get(5, TimeUnit.SECONDS);
            assertEquals("INTERACTION_REQUIRED", missing.getStatus());
            assertEquals(0, resumes.get());
            var result =
                    service.start(
                                    identity,
                                    request(
                                            "optional-explicit",
                                            Map.of(
                                                    "steps",
                                                    List.of(Map.of("userInput", "action")),
                                                    "interactions",
                                                    List.of(
                                                            question,
                                                            Map.of(
                                                                    "type",
                                                                    "approval",
                                                                    "approved",
                                                                    true,
                                                                    "toolNames",
                                                                    List.of("action"))))))
                            .get(5, TimeUnit.SECONDS);
            assertEquals("COMPLETED", result.getStatus());
            assertEquals(1, resumes.get());
            assertTrue(
                    result.getEvents().stream()
                            .anyMatch(
                                    e ->
                                            "evaluation.optional_question_skipped"
                                                    .equals(e.getEventName())));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.start(
                                    identity,
                                    request(
                                            "optional-approval",
                                            Map.of(
                                                    "steps",
                                                    List.of(Map.of("userInput", "action")),
                                                    "interactions",
                                                    List.of(
                                                            Map.of(
                                                                    "type",
                                                                    "approval",
                                                                    "optional",
                                                                    true,
                                                                    "approved",
                                                                    true,
                                                                    "toolNames",
                                                                    List.of("action")))))));
        }
    }

    @Test
    void optionalQuestionStillRequiresAnAnswerWhenItActuallyOccurs() throws Exception {
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        events.accept(event(AgentRuntimeEvent.Type.ASK_USER_REQUIRED, "scope?"));
                    }

                    public void resume(
                            ExecutionIdentity identity,
                            String session,
                            AgentRuntimeEvent pending,
                            EvaluationProtocol.Interaction script,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        throw new IllegalStateException("ASK_USER_SCRIPT_MISMATCH");
                    }
                };
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var result =
                    service.start(
                                    identity,
                                    request(
                                            "optional-question",
                                            Map.of(
                                                    "steps",
                                                    List.of(Map.of("userInput", "action")),
                                                    "interactions",
                                                    List.of(
                                                            Map.of(
                                                                    "type",
                                                                    "ask_user",
                                                                    "optional",
                                                                    true)))))
                            .get(5, TimeUnit.SECONDS);
            assertEquals("INTERACTION_REQUIRED", result.getStatus());
        }
    }

    @Test
    void unexpectedQuestionPreservesReceiptsAndDoesNotInventAnAnswer() throws Exception {
        var registry = new EvaluationSessionRegistry();
        AtomicInteger cancelled = new AtomicInteger();
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        registry.find(identity.getOwnerKey(), session)
                                .record(
                                        "create_resource",
                                        Map.of("name", "example"),
                                        ToolResultBlock.text("{\"id\":42}"));
                        events.accept(
                                event(
                                        AgentRuntimeEvent.Type.ASK_USER_REQUIRED,
                                        "Was that helpful?"));
                    }

                    public void cancel(ExecutionIdentity identity, String session) {
                        cancelled.incrementAndGet();
                    }
                };
        try (var service = new EvaluationRunService(host, registry, 1)) {
            var req =
                    request(
                            "followup",
                            Map.of(
                                    "steps",
                                    List.of(
                                            Map.of("userInput", "create example"),
                                            Map.of("userInput", "next"))));
            req.setFixture(Map.of("modeLabel", "RECORD"));
            var result = service.start(identity, req).get(5, TimeUnit.SECONDS);
            assertEquals("INTERACTION_REQUIRED", result.getStatus());
            assertNull(result.getErrorCode());
            assertEquals(1, result.getFixtureRecord().size());
            assertEquals(1, cancelled.get());
            var execution = (Map<?, ?>) result.getActualOutput().get("evaluationExecution");
            assertEquals(1, execution.get("unexecutedStepCount"));
            assertEquals("ask_user", ((Map<?, ?>) execution.get("pendingInteraction")).get("type"));
            assertFalse(result.getActualOutput().containsKey("lastAgentMessage"));
            assertThrows(NoSuchElementException.class, () -> service.status(identity, "followup"));
        }
    }

    @Test
    void cancellationInterruptsExecutionAndDoesNotGenerateAReply() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        started.countDown();
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            throw new IllegalStateException("interrupted");
                        }
                    }

                    public void cancel(ExecutionIdentity identity, String session) {
                        cancelled.incrementAndGet();
                    }
                };
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var completion =
                    service.start(
                            identity,
                            request(
                                    "cancel",
                                    Map.of("steps", List.of(Map.of("userInput", "wait")))));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            service.cancel(identity, "cancel");
            assertEquals("CANCELLED", completion.get(5, TimeUnit.SECONDS).getStatus());
            assertTrue(cancelled.get() > 0);
        }
    }

    @Test
    void terminalAgentFailureReturnsEvidenceForJudgingInsteadOfInfrastructureError()
            throws Exception {
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        events.accept(event(AgentRuntimeEvent.Type.TURN_FAILED, "tool failed"));
                    }
                };
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var result =
                    service.start(
                                    identity,
                                    request(
                                            "agent-failed",
                                            Map.of("steps", List.of(Map.of("userInput", "do it")))))
                            .get(5, TimeUnit.SECONDS);
            assertEquals("AGENT_FAILED", result.getStatus());
            assertNull(result.getErrorCode());
            assertEquals(
                    "AGENT_FAILED",
                    ((Map<?, ?>) result.getActualOutput().get("evaluationExecution"))
                            .get("status"));
            assertTrue(
                    result.getEvents().stream().anyMatch(e -> "MESSAGE".equals(e.getEventType())));
        }
    }

    @Test
    void replayMissFailsEvenIfTheModelContinuesAndUnsupportedFaultsAreRejected() throws Exception {
        EvaluationSessionRegistry registry = new EvaluationSessionRegistry();
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity identity,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        registry.find(identity.getOwnerKey(), session).replay("query", Map.of());
                        events.accept(
                                event(
                                        AgentRuntimeEvent.Type.TURN_COMPLETED,
                                        "apparently succeeded"));
                    }
                };
        try (var service = new EvaluationRunService(host, registry, 1)) {
            var r = request("miss", Map.of("steps", List.of(Map.of("userInput", "query"))));
            r.setFixture(Map.of("modeLabel", "REPLAY"));
            var completion = service.start(identity, r);
            assertEquals("FIXTURE_MISS", completion.get(5, TimeUnit.SECONDS).getErrorCode());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.start(
                                    identity,
                                    request(
                                            "faults",
                                            Map.of(
                                                    "steps",
                                                    List.of(Map.of("userInput", "query")),
                                                    "faults",
                                                    List.of(
                                                            Map.of(
                                                                    "tool", "query", "times",
                                                                    -1))))));
        }
    }

    @Test
    void completionCallbackCanSubmitTheNextRunBeforeItsWorkerReturns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity owner,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        if (calls.incrementAndGet() == 1) {
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        events.accept(event(AgentRuntimeEvent.Type.TURN_COMPLETED, "done"));
                    }
                };
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var first =
                    service.start(
                            identity,
                            request(
                                    "handoff-first",
                                    Map.of("steps", List.of(Map.of("userInput", "first")))));
            var next =
                    first.thenCompose(
                            result ->
                                    service.start(
                                            identity,
                                            request(
                                                    "handoff-next",
                                                    Map.of(
                                                            "steps",
                                                            List.of(
                                                                    Map.of(
                                                                            "userInput",
                                                                            "next"))))));
            release.countDown();
            assertEquals("COMPLETED", next.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void handoffQueueDoesNotIncreaseTheAdmissionLimit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity owner,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        started.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                        events.accept(event(AgentRuntimeEvent.Type.TURN_COMPLETED, "done"));
                    }
                };
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var first =
                    service.start(
                            identity,
                            request(
                                    "limit-first",
                                    Map.of("steps", List.of(Map.of("userInput", "first")))));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(
                    RejectedExecutionException.class,
                    () ->
                            service.start(
                                    identity,
                                    request(
                                            "limit-next",
                                            Map.of(
                                                    "steps",
                                                    List.of(Map.of("userInput", "next"))))));
            release.countDown();
            assertEquals("COMPLETED", first.get(5, TimeUnit.SECONDS).getStatus());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeSettlesAQueuedHandoffWithoutExecutingIt() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch holdCallback = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CompletableFuture<EvaluationProtocol.Status>> waiting =
                new AtomicReference<>();
        EvaluationHost host =
                new FakeHost() {
                    public void start(
                            ExecutionIdentity owner,
                            String session,
                            String requestId,
                            EvaluationProtocol.Step step,
                            Duration timeout,
                            Consumer<AgentRuntimeEvent> events) {
                        calls.incrementAndGet();
                        try {
                            releaseFirst.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                        events.accept(event(AgentRuntimeEvent.Type.TURN_COMPLETED, "done"));
                    }
                };
        try (var service = new EvaluationRunService(host, new EvaluationSessionRegistry(), 1)) {
            var first =
                    service.start(
                            identity,
                            request(
                                    "close-first",
                                    Map.of("steps", List.of(Map.of("userInput", "first")))));
            first.thenAccept(
                    result -> {
                        waiting.set(
                                service.start(
                                        identity,
                                        request(
                                                "close-queued",
                                                Map.of(
                                                        "steps",
                                                        List.of(Map.of("userInput", "next"))))));
                        queued.countDown();
                        try {
                            holdCallback.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                    });
            releaseFirst.countDown();
            assertTrue(queued.await(2, TimeUnit.SECONDS));
            service.close();
            assertEquals("CANCELLED", waiting.get().get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(1, calls.get());
            assertThrows(
                    NoSuchElementException.class, () -> service.status(identity, "close-queued"));
            assertThrows(
                    RejectedExecutionException.class,
                    () ->
                            service.start(
                                    identity,
                                    request(
                                            "closed",
                                            Map.of(
                                                    "steps",
                                                    List.of(Map.of("userInput", "next"))))));
        } finally {
            releaseFirst.countDown();
            holdCallback.countDown();
        }
    }

    private static class FakeHost implements EvaluationHost {
        public void start(
                ExecutionIdentity identity,
                String session,
                String requestId,
                EvaluationProtocol.Step step,
                Duration timeout,
                Consumer<AgentRuntimeEvent> events) {}

        public void resume(
                ExecutionIdentity identity,
                String session,
                AgentRuntimeEvent pending,
                EvaluationProtocol.Interaction script,
                Duration timeout,
                Consumer<AgentRuntimeEvent> events) {
            throw new IllegalStateException("Unexpected resume");
        }

        public void cancel(ExecutionIdentity identity, String session) {}
    }
}
