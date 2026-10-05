package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.domain.artifact.ArtifactDescriptor;
import dev.horizen.agent.domain.artifact.ArtifactEventCollector;
import dev.horizen.agent.domain.askuser.AskUserEventCollector;
import dev.horizen.agent.domain.presentation.PresentationBlock;
import dev.horizen.agent.domain.presentation.PresentationEventCollector;
import dev.horizen.agent.domain.presentation.PresentationOutput;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在运行时适配器边界转换 AgentScope 事件和消息。 */
final class HarnessAgentEventMapper {
    /** NOTICE事件名称使用的固定标识或协议文本。 */
    public static final String NOTICE_EVENT_NAME = "horizen.execution_notice";

    /** 当前配置的 Agent 实例，承担模型与工具循环执行。 */
    private final HarnessAgent agent;

    /**
     * 创建HarnessAgent事件映射器，初始化该组件所需的状态、配置或依赖。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     */
    HarnessAgentEventMapper(HarnessAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    /**
     * 映射HarnessAgent事件映射器。
     *
     * @param request 当前操作的请求参数。
     * @param source 待解析或转换的来源对象。
     * @param turnStartedAt 执行开始的时间，用于记录对应生命周期节点。
     * @param stepStarts 步骤启动次数的索引映射，供按键查找或归并当前组件的数据。
     * @param artifactEvents 当前HarnessAgent事件映射器持有的产物事件集合对象，供相应处理步骤使用。
     * @param presentationEvents 当前HarnessAgent事件映射器持有的呈现事件集合对象，供相应处理步骤使用。
     * @param askUserEvents 当前HarnessAgent事件映射器持有的提问用户事件集合对象，供相应处理步骤使用。
     * @param runtimeContext 当前HarnessAgent事件映射器持有的运行时上下文对象，供相应处理步骤使用。
     * @return 本次处理得到的结果集合。
     */
    List<AgentRuntimeEvent> map(
            AgentTurnRequest request,
            AgentEvent source,
            long turnStartedAt,
            Map<String, Long> stepStarts,
            ArtifactEventCollector artifactEvents,
            PresentationEventCollector presentationEvents,
            AskUserEventCollector askUserEvents,
            RuntimeContext runtimeContext) {
        if (source instanceof RequireExternalExecutionEvent external) {
            var ask = askUserEvents.poll();
            if (ask != null) {
                return List.of(
                        withProvenance(
                                event(
                                                request,
                                                AgentRuntimeEvent.Type.ASK_USER_REQUIRED,
                                                ask.getAskUserId())
                                        .status("waiting_ask_user")
                                        .toolName("ask_user")
                                        .details(ask)
                                        .build(),
                                source));
            }
        }
        if (source instanceof ToolResultEndEvent end) {
            String status = end.getState() == null ? "success" : end.getState().getValue();
            AgentRuntimeEvent completed =
                    withProvenance(
                            event(
                                            request,
                                            AgentRuntimeEvent.Type.TOOL_COMPLETED,
                                            end.getToolCallId())
                                    .text("error".equals(status) ? "工具执行出现异常，正在尝试继续处理。" : null)
                                    .status(status)
                                    .toolName(end.getToolCallName())
                                    .durationMs(
                                            stepDuration(
                                                    stepStarts,
                                                    stepKey("tool", source, end.getToolCallId())))
                                    .build(),
                            source);
            List<ArtifactDescriptor> artifacts = artifactEvents.drain();
            List<PresentationBlock> presentations = presentationEvents.drain(end.getToolCallId());
            boolean todoUpdated = "todo_write".equals(end.getToolCallName());
            if (artifacts.isEmpty() && presentations.isEmpty() && !todoUpdated) {
                return List.of(completed);
            }
            List<AgentRuntimeEvent> mapped = new ArrayList<>();
            mapped.add(completed);
            if (todoUpdated) {
                mapped.add(
                        withProvenance(
                                event(
                                                request,
                                                AgentRuntimeEvent.Type.TODO_UPDATED,
                                                end.getToolCallId())
                                        .status("success")
                                        .toolName("todo_write")
                                        .details(todoDetails(runtimeContext))
                                        .build(),
                                source));
            }
            int artifactPosition = 0;
            for (ArtifactDescriptor artifact : artifacts) {
                PresentationBlock block =
                        PresentationBlock.artifactCard(artifact, artifactPosition++);
                mapped.add(
                        withProvenance(
                                event(
                                                request,
                                                AgentRuntimeEvent.Type.PRESENTATION_CREATED,
                                                block.getBlockId())
                                        .status("success")
                                        .toolName(end.getToolCallName())
                                        .details(new PresentationOutput(end.getToolCallId(), block))
                                        .build(),
                                source));
            }
            for (PresentationBlock block : presentations) {
                mapped.add(
                        withProvenance(
                                event(
                                                request,
                                                AgentRuntimeEvent.Type.PRESENTATION_CREATED,
                                                block.getBlockId())
                                        .status("success")
                                        .toolName(end.getToolCallName())
                                        .details(new PresentationOutput(end.getToolCallId(), block))
                                        .build(),
                                source));
            }
            return List.copyOf(mapped);
        }
        List<AgentRuntimeEvent> notices = new ArrayList<>();
        AgentRuntimeEvent mapped =
                mapSingle(request, source, turnStartedAt, stepStarts, runtimeContext, notices);
        if (mapped != null) notices.add(mapped);
        return notices.stream().map(item -> withProvenance(item, source)).toList();
    }

    /**
     * 映射Single。
     *
     * @param request 当前操作的请求参数。
     * @param source 待解析或转换的来源对象。
     * @param turnStartedAt 执行开始的时间，用于记录对应生命周期节点。
     * @param stepStarts 步骤启动次数的索引映射，供按键查找或归并当前组件的数据。
     * @param runtimeContext 当前HarnessAgent事件映射器持有的运行时上下文对象，供相应处理步骤使用。
     * @param notices notices的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次操作返回的Agent运行时事件结果。
     */
    private AgentRuntimeEvent mapSingle(
            AgentTurnRequest request,
            AgentEvent source,
            long turnStartedAt,
            Map<String, Long> stepStarts,
            RuntimeContext runtimeContext,
            List<AgentRuntimeEvent> notices) {
        var context =
                RuntimeEventMappingContext.builder()
                        .request(request)
                        .turnStartedAt(turnStartedAt)
                        .stepStarts(stepStarts)
                        .runtimeContext(runtimeContext)
                        .notices(notices)
                        .agent(agent)
                        .build();
        AgentRuntimeEvent event;
        event = RuntimeNoticeEventMapper.map(context, source);
        if (event != null) return event;
        event = SubagentEventMapper.map(context, source);
        if (event != null) return event;
        event = InteractionEventMapper.map(context, source);
        if (event != null) return event;
        event = ModelEventMapper.map(context, source);
        if (event != null) return event;
        event = ToolDeltaEventMapper.map(context, source);
        if (event != null) return event;
        event = RuntimeResultEventMapper.map(context, source);
        if (event != null) return event;
        return null;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HarnessAgentEventMapper处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次处理得到的结果集合。
     */
    private static List<Map<String, Object>> todoDetails(RuntimeContext context) {
        AgentState state = context == null ? null : context.getAgentState();
        if (state == null || state.getTasksContext() == null) {
            return List.of();
        }
        return state.getTasksContext().getTasks().stream()
                .map(
                        task -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("id", task.getId());
                            value.put("content", task.getSubject());
                            value.put("status", task.getState().getWire());
                            Object priority =
                                    task.getMetadata() == null
                                            ? null
                                            : task.getMetadata().get("priority");
                            if (priority != null) value.put("priority", priority.toString());
                            return value;
                        })
                .toList();
    }

    // 完整列出当前固定版本的上游枚举，升级依赖时必须检查新增停止原因。

}
