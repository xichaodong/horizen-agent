package dev.horizen.agent.web.evaluation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.horizen.agent.evaluation.EvaluationProtocol;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.interaction.InteractionApi;

import lombok.AllArgsConstructor;
import lombok.Data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

class WebEvaluationHostTest {
    @Data
    @AllArgsConstructor
    static class QuestionsEnvelope {
        private String questionsJson;
    }

    private final AgentService agent = mock(AgentService.class);
    private final ExecutionIdentity identity = new ExecutionIdentity("test-owner", "test-actor");
    private final WebEvaluationHost host = new WebEvaluationHost(agent);

    @BeforeEach
    void settled() {
        when(agent.executionSettled(any(), any())).thenReturn(true);
    }

    private AgentRuntimeEvent event(AgentRuntimeEvent.Type type) {
        return new AgentRuntimeEvent(
                type,
                "turn",
                "session",
                "question",
                "title",
                "done",
                "success",
                null,
                null,
                null,
                null);
    }

    @Test
    void approvalMustMatchPendingToolsBeforeCallingProductionDecisionService() {
        when(agent.pendingApprovals(eq(identity), any()))
                .thenReturn(
                        new InteractionApi.ApprovalsResponse(
                                "session",
                                "turn",
                                List.of(
                                        new InteractionApi.ApprovalResponse(
                                                "approval-id",
                                                "turn",
                                                "call",
                                                "update",
                                                Map.of(),
                                                "pending",
                                                null,
                                                null))));
        when(agent.replayExecutionEvents(eq(identity), any()))
                .thenReturn(Flux.just(event(AgentRuntimeEvent.Type.TURN_COMPLETED)));
        var script = new EvaluationProtocol.Interaction();
        script.setType("approval");
        script.setApproved(false);
        script.setToolNames(List.of("unexpected"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        host.resume(
                                identity,
                                "session",
                                event(AgentRuntimeEvent.Type.APPROVAL_REQUIRED),
                                script,
                                Duration.ofSeconds(1),
                                ignored -> {
                                }));
        verify(agent, never()).decideApproval(any(), any());
        script.setToolNames(List.of("update"));
        host.resume(
                identity,
                "session",
                event(AgentRuntimeEvent.Type.APPROVAL_REQUIRED),
                script,
                Duration.ofSeconds(1),
                ignored -> {
                });
        verify(agent)
                .decideApproval(
                        eq(identity),
                        eq(
                                new InteractionApi.ApprovalDecisionRequest(
                                        "session",
                                        "turn",
                                        List.of(
                                                new InteractionApi.ApprovalChoice(
                                                        "approval-id", false)))));
    }

    @Test
    void clarificationUsesProductionAnswerServiceAndResumesSameTurn() {
        when(agent.replayExecutionEvents(eq(identity), any()))
                .thenReturn(Flux.just(event(AgentRuntimeEvent.Type.TURN_COMPLETED)));
        var script = new EvaluationProtocol.Interaction();
        script.setType("ask_user");
        script.setSkip(true);
        host.resume(
                identity,
                "session",
                event(AgentRuntimeEvent.Type.ASK_USER_REQUIRED),
                script,
                Duration.ofSeconds(1),
                ignored -> {
                });
        verify(agent)
                .answerAskUser(
                        eq(identity),
                        eq(new InteractionApi.AskUserAnswerRequest("question", List.of(), true)));
    }

    @Test
    void exactOptionLabelResolvesGeneratedIdsAndRejectsUnexpectedChoices() {
        when(agent.replayExecutionEvents(eq(identity), any()))
                .thenReturn(Flux.just(event(AgentRuntimeEvent.Type.TURN_COMPLETED)));
        var pending = event(AgentRuntimeEvent.Type.ASK_USER_REQUIRED);
        pending.setDetails(
                new QuestionsEnvelope(
                        "[{\"questionId\":\"generated\",\"options\":[{\"optionId\":\"yes\",\"label\":\"Confirm\"}]}]"));
        var script = new EvaluationProtocol.Interaction();
        script.setType("ask_user");
        script.setSelectedOptionLabels(List.of("Other"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        host.resume(
                                identity,
                                "session",
                                pending,
                                script,
                                Duration.ofSeconds(1),
                                ignored -> {
                                }));
        verify(agent, never()).answerAskUser(any(), any());
        script.setSelectedOptionLabels(List.of("Confirm", "Confirm (submit)"));
        host.resume(identity, "session", pending, script, Duration.ofSeconds(1), ignored -> {
        });
        verify(agent)
                .answerAskUser(
                        eq(identity),
                        eq(
                                new InteractionApi.AskUserAnswerRequest(
                                        "question",
                                        List.of(
                                                Map.of(
                                                        "questionId",
                                                        "generated",
                                                        "selectedOptionIds",
                                                        List.of("yes"))),
                                        false)));
    }
}
