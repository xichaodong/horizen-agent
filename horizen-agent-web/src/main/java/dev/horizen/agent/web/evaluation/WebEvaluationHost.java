package dev.horizen.agent.web.evaluation;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.evaluation.EvaluationHost;
import dev.horizen.agent.evaluation.EvaluationProtocol;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.interaction.InteractionApi;
import dev.horizen.agent.web.api.session.SessionApi;

import lombok.RequiredArgsConstructor;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Agent 专属交互逻辑在此实现，不放入评测服务器。 */
@RequiredArgsConstructor
public final class WebEvaluationHost implements EvaluationHost {
    /** 当前配置的 Agent 实例，承担模型与工具循环执行。 */
    private final AgentService agent;

    /**
     * 启动Web评测宿主。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param session 当前Web评测宿主使用的会话，供其处理与状态记录使用。
     * @param requestId 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     * @param step 当前Web评测宿主持有的步骤对象，供相应处理步骤使用。
     * @param timeout 本次等待允许持续的最长时间。
     * @param events 当前执行或历史事件集合，供持久化、回放与观测使用。
     */
    public void start(
            ExecutionIdentity identity,
            String session,
            String requestId,
            EvaluationProtocol.Step step,
            Duration timeout,
            Consumer<AgentRuntimeEvent> events) {
        consume(
                identity,
                session,
                agent.executionEvents(
                        identity,
                        new ChatApi.ChatRequest(
                                session, step.getUserInput(), requestId, step.getArtifactIds())),
                timeout,
                events);
    }

    /**
     * 恢复Web评测宿主。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param session 当前Web评测宿主使用的会话，供其处理与状态记录使用。
     * @param pending 尚未完成处理的工作或计数，供刷新、关闭与容量控制使用。
     * @param script 当前Web评测宿主持有的script对象，供相应处理步骤使用。
     * @param timeout 本次等待允许持续的最长时间。
     * @param events 当前执行或历史事件集合，供持久化、回放与观测使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void resume(
            ExecutionIdentity identity,
            String session,
            AgentRuntimeEvent pending,
            EvaluationProtocol.Interaction script,
            Duration timeout,
            Consumer<AgentRuntimeEvent> events) {
        if (pending.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED
                && "approval".equals(script.getType())) {
            var approvals =
                    agent.pendingApprovals(
                                    identity,
                                    new InteractionApi.ApprovalQueryRequest(
                                            session, pending.getTurnId()))
                            .getApprovals();
            var names =
                    approvals.stream()
                            .map(InteractionApi.ApprovalResponse::getToolName)
                            .sorted()
                            .toList();
            if (script.getApproved() == null
                    || !names.equals(script.getToolNames().stream().sorted().toList()))
                throw new IllegalStateException("APPROVAL_SCRIPT_TOOL_MISMATCH");
            agent.decideApproval(
                    identity,
                    new InteractionApi.ApprovalDecisionRequest(
                            session,
                            pending.getTurnId(),
                            approvals.stream()
                                    .map(
                                            a ->
                                                    new InteractionApi.ApprovalChoice(
                                                            a.getApprovalId(),
                                                            script.getApproved()))
                                    .toList()));
        } else if (pending.getType() == AgentRuntimeEvent.Type.ASK_USER_REQUIRED
                && "ask_user".equals(script.getType())) {
            var answers = script.getAnswers();
            if (!script.getSelectedOptionLabels().isEmpty()) {
                if (!answers.isEmpty() || script.isSkip() || pending.getDetails() == null)
                    throw new IllegalStateException("ASK_USER_SCRIPT_MISMATCH");
                try {
                    var mapper = JsonUtils.newMapper();
                    var details = mapper.valueToTree(pending.getDetails());
                    var questions = mapper.readTree(details.path("questionsJson").asText());
                    if (!questions.isArray() || questions.size() != 1)
                        throw new IllegalStateException("ASK_USER_SCRIPT_MISMATCH");
                    var question = questions.get(0);
                    List<String> matched = new ArrayList<>();
                    for (var option : question.path("options"))
                        if (script.getSelectedOptionLabels()
                                .contains(option.path("label").asText()))
                            matched.add(option.path("optionId").asText());
                    if (matched.size() != 1
                            || matched.get(0).isBlank()
                            || question.path("questionId").asText().isBlank())
                        throw new IllegalStateException("ASK_USER_SCRIPT_MISMATCH");
                    answers =
                            List.of(
                                    Map.of(
                                            "questionId",
                                            question.path("questionId").asText(),
                                            "selectedOptionIds",
                                            matched));
                } catch (IOException error) {
                    throw new IllegalStateException("ASK_USER_SCRIPT_MISMATCH");
                }
            }
            agent.answerAskUser(
                    identity,
                    new InteractionApi.AskUserAnswerRequest(
                            pending.getId(), answers, script.isSkip()));
            events.accept(
                    AgentRuntimeEvent.builder()
                            .type(AgentRuntimeEvent.Type.ASK_USER_RESOLVED)
                            .turnId(pending.getTurnId())
                            .sessionId(session)
                            .id(pending.getId())
                            .title("Scripted answer submitted")
                            .status("resolved")
                            .toolName("ask_user")
                            .details(
                                    Map.of(
                                            "askUserId",
                                            pending.getId(),
                                            "answers",
                                            answers,
                                            "skip",
                                            script.isSkip()))
                            .build());
        } else throw new IllegalStateException("INTERACTION_SCRIPT_TYPE_MISMATCH");
        consume(
                identity,
                session,
                agent.replayExecutionEvents(
                        identity,
                        new SessionApi.SessionSubscribeRequest(session, pending.getTurnId())),
                timeout,
                events);
    }

    /**
     * 取消Web评测宿主。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param session 当前Web评测宿主使用的会话，供其处理与状态记录使用。
     */
    public void cancel(ExecutionIdentity identity, String session) {
        var current = agent.sessionExecution(identity, session);
        if (current != null && current.getCurrentTurnId() != null)
            agent.cancelSession(
                    identity,
                    new SessionApi.SessionCancelRequest(session, current.getCurrentTurnId()));
    }

    /**
     * 完成当前操作的consume步骤，按实现更新相应状态或依赖。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param session 当前Web评测宿主使用的会话，供其处理与状态记录使用。
     * @param stream 当前Web评测宿主持有的事件流对象，供相应处理步骤使用。
     * @param timeout 本次等待允许持续的最长时间。
     * @param events 当前执行或历史事件集合，供持久化、回放与观测使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void consume(
            ExecutionIdentity identity,
            String session,
            Flux<AgentRuntimeEvent> stream,
            Duration timeout,
            Consumer<AgentRuntimeEvent> events) {
        long deadline = System.nanoTime() + timeout.toNanos();
        stream.doOnNext(events)
                .takeUntil(
                        e ->
                                (e.getSource() == null || e.getSource().isBlank())
                                        && switch (e.getType()) {
                                            case TURN_COMPLETED,
                                                    TURN_FAILED,
                                                    TURN_CANCELLED,
                                                    TURN_TIMED_OUT,
                                                    APPROVAL_REQUIRED,
                                                    ASK_USER_REQUIRED ->
                                                    true;
                                            default -> false;
                                        })
                .blockLast(timeout);
        // 结果发出时，Runtime 和托管执行尚未释放 Turn。创建下一轮 Turn 或恢复交互前，
        // 需要等待释放完成。
        while (!agent.executionSettled(identity, session)) {
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException("TURN_SETTLEMENT_TIMEOUT");
            try {
                Thread.sleep(5);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Execution interrupted");
            }
        }
    }
}
