package dev.horizen.agent.context;

import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.MessageRole;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;

import lombok.RequiredArgsConstructor;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Redis 状态缺失后，从最终持久化对话消息重建全新的 AgentScope 状态。
 */
@RequiredArgsConstructor
public final class HistoryContextRecoveryMiddleware implements MiddlewareBase {
    /** 负责store对应持久化访问的仓储依赖；调用方通过端口隔离具体存储实现。 */
    private final HistoryRecoveringAgentStateStore store;

    /**
     * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
     *
     * @return 中间件执行顺序值。
     */
    @Override
    public int order() {
        return 100;
    }

    /**
     * 响应模型推理。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input 本次处理的输入。
     * @param next 将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (context == null) return next.apply(input);
        HistoryRecoveryScope scope = context.get(HistoryRecoveryScope.class);
        if (scope == null || !scope.isEligible()) {
            store.discardMissingHistory(context.getUserId(), context.getSessionId());
            return next.apply(input);
        }
        List<Msg> nonSystemInput =
                input.messages().stream()
                        .filter(message -> message.getRole() != MsgRole.SYSTEM)
                        .toList();
        AgentState state = RuntimeContext.resolveAgentState(context, agent);
        if (state == null) return next.apply(input);
        // agent_state 缺失后，AgentScope 可能已恢复旧状态键。不能在非全新状态上叠加 JDBC 对话记录。
        if (state.contextMutable().size() > nonSystemInput.size()) {
            store.discardMissingHistory(context.getUserId(), context.getSessionId());
            return next.apply(input);
        }
        List<ConversationMessage> durable =
                store.consumeMissingHistory(context.getUserId(), context.getSessionId());
        if (durable.isEmpty()) return next.apply(input);
        ToolInvocationScope toolScope = context.get(ToolInvocationScope.class);
        if (toolScope != null) {
            durable =
                    durable.stream()
                            .filter(message -> !toolScope.getTurnId().equals(message.getTurnId()))
                            .toList();
        }
        List<Msg> restored = toMessages(durable);
        List<Msg> current = new ArrayList<>(state.contextMutable());
        dropDuplicateTail(restored, current);
        restored.addAll(current);
        state.contextMutable().clear();
        state.contextMutable().addAll(restored);
        List<Msg> modelInput = new ArrayList<>();
        input.messages().stream()
                .filter(message -> message.getRole() == MsgRole.SYSTEM)
                .forEach(modelInput::add);
        modelInput.addAll(restored);
        return next.apply(new ReasoningInput(modelInput, input.tools(), input.options()));
    }

    /**
     * 转换为消息集合。
     *
     * @param history 历史的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理得到的结果集合。
     */
    private static List<Msg> toMessages(List<ConversationMessage> history) {
        List<Msg> restored = new ArrayList<>();
        for (ConversationMessage message : history) {
            if (message.getRole() == MessageRole.USER) {
                restored.add(new UserMessage(message.getContent()));
            } else if (message.getRole() == MessageRole.ASSISTANT) {
                restored.add(new AssistantMessage(message.getContent()));
            }
        }
        return restored;
    }

    /**
     * 完成当前操作的dropDuplicateTail步骤，按实现更新相应状态或依赖。
     *
     * @param history 历史的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param current 当前的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    private static void dropDuplicateTail(List<Msg> history, List<Msg> current) {
        if (history.isEmpty() || current.isEmpty()) return;
        Msg prior = history.get(history.size() - 1);
        Msg next = current.get(0);
        if (prior.getRole() == next.getRole()
                && prior.getTextContent().equals(next.getTextContent())) {
            history.remove(history.size() - 1);
        }
    }
}
