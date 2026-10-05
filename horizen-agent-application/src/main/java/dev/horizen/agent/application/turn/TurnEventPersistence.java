package dev.horizen.agent.application.turn;

import static dev.horizen.agent.common.error.Exceptions.rootCause;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.presentation.PresentationOutput;
import dev.horizen.agent.domain.presentation.PresentationRecord;
import dev.horizen.agent.domain.presentation.PresentationStore;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TransitionTurnResult;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.execution.turn.TurnTimelineEvent;
import dev.horizen.agent.execution.turn.TurnTimelineStore;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.ToolApprovalRequest;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** 持久化运行时事件并推进 Turn 的持久化状态。 */
@RequiredArgsConstructor
public final class TurnEventPersistence {
    /** 当前组件的诊断日志器。 */
    private static final Logger log = LoggerFactory.getLogger(TurnEventPersistence.class);

    /** 会话对象或会话索引，按相应的归属键定位数据。 */
    private final SessionTurnStore sessions;

    /** 审批存储或待处理审批集合，用于原执行的暂停与恢复。 */
    private final ApprovalStore approvals;

    /** 需要持久化或展示的结构化呈现块集合。 */
    private final PresentationStore presentations;

    /** 存储正式过程事件的时间线端口，供持久化与刷新恢复使用。 */
    private final TurnTimelineStore timeline;

    /** 当前执行或历史事件集合，供持久化、回放与观测使用。 */
    private final TurnEventChannel events;

    /** 执行 Agent 模型与工具循环的运行时接口。 */
    private final AgentRuntime runtime;

    /** 将运行时事件编码成正式时间线负载的转换函数。 */
    private final Function<AgentRuntimeEvent, String> timelineEncoder;

    /** 在执行开始或准备期间登记续租跟踪的回调。 */
    private final Consumer<AgentTurnRequest> trackLease;

    /** 在执行段结束或暂停后移除续租跟踪的回调。 */
    private final Consumer<AgentTurnRequest> untrackLease;

    /**
     * 完成当前操作的preparing步骤，按实现更新相应状态或依赖。
     *
     * @param turn 当前执行事件持久化持有的执行对象，供相应处理步骤使用。
     */
    public void preparing(AgentTurnRequest turn) {
        trackLease.accept(turn);
    }

    /**
     * 完成当前操作的stopTrackingLease步骤，按实现更新相应状态或依赖。
     *
     * @param turn 当前执行事件持久化持有的执行对象，供相应处理步骤使用。
     */
    public void stopTrackingLease(AgentTurnRequest turn) {
        untrackLease.accept(turn);
    }

    /**
     * 把运行时事件转换为正式执行事实、交互记录与分布式回放增量。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前执行事件持久化持有的执行对象，供相应处理步骤使用。
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @param tools 当前执行事件持久化持有的工具集合对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void observe(
            ExecutionIdentity identity,
            AgentTurnRequest turn,
            AgentRuntimeEvent event,
            ToolTimelineProjection tools) {
        if (events == null
                || sessions == null
                || (event.getType() == AgentRuntimeEvent.Type.THINKING_DELTA
                        || event.getType() == AgentRuntimeEvent.Type.MODEL_STARTED
                        || event.getType() == AgentRuntimeEvent.Type.MODEL_COMPLETED)) return;
        tools.accept(event);
        if (transientEvent(event)) {
            events.publish(turn.getOwnerKey(), event);
            return;
        }
        if (event.getType() == AgentRuntimeEvent.Type.TOOL_COMPLETED) {
            // MySQL 仅保存一次完整事实。输入输出片段已流式发送，Redis/SSE 保留原来的小型结束事件。
            publish(
                    turn.getOwnerKey(),
                    turn.getSessionId(),
                    turn.getTurnId(),
                    event,
                    tools.complete(event));
            return;
        }
        if (event.getType() == AgentRuntimeEvent.Type.TURN_STARTED) {
            publish(turn, event);
            trackLease.accept(turn);
            return;
        }
        if (event.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED) {
            untrackLease.accept(turn);
            persistApprovals(identity, turn, event);
            pause(turn, TurnStatus.WAITING_APPROVAL, "approval");
            publish(turn, event);
            return;
        }
        if (event.getType() == AgentRuntimeEvent.Type.ASK_USER_REQUIRED) {
            untrackLease.accept(turn);
            pause(turn, TurnStatus.WAITING_ASK_USER, "ask_user");
            publish(turn, event);
            return;
        }
        if (event.getType() == AgentRuntimeEvent.Type.PRESENTATION_CREATED) {
            persistPresentation(turn, event);
        }
        if (!terminal(event.getType())) {
            publish(turn, event);
            return;
        }
        untrackLease.accept(turn);
        TurnStatus status =
                switch (event.getType()) {
                    case TURN_COMPLETED -> TurnStatus.COMPLETED;
                    case TURN_FAILED -> TurnStatus.FAILED;
                    case TURN_CANCELLED -> TurnStatus.CANCELLED;
                    case TURN_TIMED_OUT -> TurnStatus.TIMED_OUT;
                    default -> throw new IllegalStateException("非终态事件不能完成 Turn");
                };
        String failureCode =
                event.getDetails() instanceof Map<?, ?> details && details.get("errorCode") != null
                        ? details.get("errorCode").toString()
                        : null;
        TransitionTurnResult result =
                sessions.transitionTurn(
                        new TransitionTurnCommand(
                                turn.getOwnerKey(),
                                turn.getSessionId(),
                                turn.getTurnId(),
                                status,
                                null,
                                null,
                                Instant.now(),
                                failureCode,
                                status == TurnStatus.COMPLETED
                                        ? "assistant-" + turn.getTurnId()
                                        : null,
                                status == TurnStatus.COMPLETED ? event.getText() : null));
        if (result.getOutcome() != TransitionTurnResult.Outcome.UPDATED
                && result.getOutcome() != TransitionTurnResult.Outcome.ALREADY_IN_STATE) {
            log.warn(
                    "Turn terminal state was not persisted [turnId={}, outcome={}]",
                    turn.getTurnId(),
                    result.getOutcome());
        }
        publish(turn, event);
    }

    /**
     * 持久化或回放写入失败时结束原执行，避免仅在浏览器侧显示失败而事实状态仍在运行。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前执行事件持久化持有的执行对象，供相应处理步骤使用。
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @param error 本次失败的异常，用于分类、传播或诊断。
     */
    public void failAfterWrite(
            ExecutionIdentity identity,
            AgentTurnRequest turn,
            AgentRuntimeEvent event,
            RuntimeException error) {
        if (sessions == null) return;
        untrackLease.accept(turn);
        String failureCode =
                error instanceof ToolTimelineProjection.SnapshotLimitException
                        ? "TOOL_HISTORY_BUFFER_LIMIT"
                        : error instanceof TurnEventChannel.EventWriteException
                                ? "REDIS_EVENT_WRITE_FAILED"
                                : "EVENT_PERSISTENCE_FAILED";
        runtime.failCurrentTurn(
                identity.getOwnerKey(), turn.getSessionId(), turn.getTurnId(), failureCode);
        sessions.transitionTurn(
                new TransitionTurnCommand(
                        identity.getOwnerKey(),
                        turn.getSessionId(),
                        turn.getTurnId(),
                        TurnStatus.FAILED,
                        null,
                        null,
                        Instant.now(),
                        failureCode,
                        null,
                        null));
        log.warn(
                "Turn stopped because event persistence failed "
                        + "[turnId={}, type={}, failureCode={}, cause={}]",
                turn.getTurnId(),
                event.getType(),
                failureCode,
                rootCause(error).getClass().getSimpleName());
    }

    /**
     * 将当前执行事件写入分布式观察通道。
     *
     * @param turn 当前执行事件持久化持有的执行对象，供相应处理步骤使用。
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @return 本次操作返回的长整型结果。
     */
    long publish(AgentTurnRequest turn, AgentRuntimeEvent event) {
        return publish(turn.getOwnerKey(), turn.getSessionId(), turn.getTurnId(), event);
    }

    /**
     * 将当前执行事件写入分布式观察通道。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @return 本次操作返回的长整型结果。
     */
    public long publish(String ownerKey, String sessionId, String turnId, AgentRuntimeEvent event) {
        return publish(ownerKey, sessionId, turnId, event, event);
    }

    /**
     * 将当前执行事件写入分布式观察通道。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @param durableEvent 当前执行事件持久化持有的持久事件对象，供相应处理步骤使用。
     * @return 本次操作返回的长整型结果。
     */
    private long publish(
            String ownerKey,
            String sessionId,
            String turnId,
            AgentRuntimeEvent event,
            AgentRuntimeEvent durableEvent) {
        if (!durable(event)) return 0L;
        TurnTimelineEvent saved =
                timeline.append(
                        ownerKey,
                        sessionId,
                        turnId,
                        timelineEncoder.apply(durableEvent),
                        Instant.now());
        events.publish(ownerKey, event, saved.getSequence());
        return saved.getSequence();
    }

    /**
     * 记录执行的等待交互状态，并释放当前执行段的租约跟踪。
     *
     * @param turn 当前执行事件持久化持有的执行对象，供相应处理步骤使用。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param reason 当前执行事件持久化使用的原因，供其处理与状态记录使用。
     */
    private void pause(AgentTurnRequest turn, TurnStatus status, String reason) {
        TransitionTurnResult paused =
                sessions.transitionTurn(
                        new TransitionTurnCommand(
                                turn.getOwnerKey(),
                                turn.getSessionId(),
                                turn.getTurnId(),
                                status,
                                null,
                                null,
                                Instant.now(),
                                null,
                                null,
                                null));
        if (paused.getOutcome() != TransitionTurnResult.Outcome.UPDATED
                && paused.getOutcome() != TransitionTurnResult.Outcome.ALREADY_IN_STATE) {
            log.warn(
                    "Turn {} pause was not persisted [turnId={}, outcome={}]",
                    reason,
                    turn.getTurnId(),
                    paused.getOutcome());
        }
    }

    /**
     * 按执行与归属范围持久化结构化呈现块，支持刷新后的恢复。
     *
     * @param turn 当前执行事件持久化持有的执行对象，供相应处理步骤使用。
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void persistPresentation(AgentTurnRequest turn, AgentRuntimeEvent event) {
        if (presentations == null || !(event.getDetails() instanceof PresentationOutput output)) {
            throw new IllegalStateException("presentation event is missing its typed output");
        }
        presentations.createOrFind(
                new PresentationRecord(
                        turn.getOwnerKey(),
                        turn.getSessionId(),
                        turn.getTurnId(),
                        output.getToolCallId(),
                        event.getToolName(),
                        output.getBlock(),
                        Instant.now()));
    }

    /**
     * 保存原执行产生的待确认工具调用，使决定可以在其他实例提交。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param turn 当前执行事件持久化持有的执行对象，供相应处理步骤使用。
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void persistApprovals(
            ExecutionIdentity identity, AgentTurnRequest turn, AgentRuntimeEvent event) {
        if (!(event.getDetails() instanceof List<?> values)) {
            throw new IllegalStateException("审批事件缺少工具列表");
        }
        Instant now = Instant.now();
        AgentTurn productTurn =
                sessions.findTurn(turn.getOwnerKey(), turn.getTurnId()).orElseThrow();
        List<ApprovalRequest> records =
                values.stream()
                        .filter(ToolApprovalRequest.class::isInstance)
                        .map(ToolApprovalRequest.class::cast)
                        .map(
                                request ->
                                        new ApprovalRequest(
                                                        turn.getOwnerKey(),
                                                        turn.getSessionId(),
                                                        turn.getTurnId(),
                                                        UUID.nameUUIDFromBytes(
                                                                        (turn.getOwnerKey()
                                                                                        + '\0'
                                                                                        + turn
                                                                                                .getTurnId()
                                                                                        + '\0'
                                                                                        + request
                                                                                                .getToolCallId())
                                                                                .getBytes(
                                                                                        StandardCharsets
                                                                                                .UTF_8))
                                                                .toString(),
                                                        request.getReplyId(),
                                                        request.getToolCallId(),
                                                        request.getToolName(),
                                                        request.getContent(),
                                                        JsonUtils.toJson(request.getInput()),
                                                        ApprovalStatus.PENDING,
                                                        identity.getActorId(),
                                                        productTurn.getDeadlineAt(),
                                                        null,
                                                        null,
                                                        now,
                                                        now,
                                                        0)
                                                .withPresentationJson(
                                                        JsonUtils.toJson(
                                                                request.getPresentation())))
                        .toList();
        if (records.size() != values.size()) {
            throw new IllegalStateException("审批事件包含未知数据类型");
        }
        approvals.createPending(records);
    }

    /**
     * 判断事件是否需要进入正式会话时间线。
     *
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean durable(AgentRuntimeEvent event) {
        return event.getType() != AgentRuntimeEvent.Type.THINKING_DELTA
                && event.getType() != AgentRuntimeEvent.Type.MODEL_STARTED
                && event.getType() != AgentRuntimeEvent.Type.MODEL_COMPLETED
                && !transientEvent(event);
    }

    /**
     * 判断事件是否属于当前执行的临时增量。
     *
     * @param event 当前执行事件持久化持有的事件对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean transientEvent(AgentRuntimeEvent event) {
        return event.getType() == AgentRuntimeEvent.Type.THINKING_DELTA
                || event.getType() == AgentRuntimeEvent.Type.TEXT_DELTA
                || event.getType() == AgentRuntimeEvent.Type.TOOL_INPUT_DELTA
                || event.getType() == AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA;
    }

    /**
     * 判断事件是否表示执行已经进入结束状态。
     *
     * @param type 当前操作使用的目标类型或类别。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public static boolean terminal(AgentRuntimeEvent.Type type) {
        return type == AgentRuntimeEvent.Type.TURN_COMPLETED
                || type == AgentRuntimeEvent.Type.TURN_FAILED
                || type == AgentRuntimeEvent.Type.TURN_CANCELLED
                || type == AgentRuntimeEvent.Type.TURN_TIMED_OUT;
    }
}
