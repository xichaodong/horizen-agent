package dev.horizen.agent.context;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 在批量压缩时将会话开头移出压缩区，并在模型调用前原样放回。 */
public final class ProtectedHeadContextMiddleware {
    /** 开头键的固定取值，用于相应策略和边界判断。 */
    private static final String HEAD_KEY = ProtectedHeadContextMiddleware.class.getName();

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private ProtectedHeadContextMiddleware() {}

    /**
     * 在处理前准备压缩。
     *
     * @param protectedMessages 当前受保护开头上下文中间件使用的受保护消息集合，供其处理与状态记录使用。
     * @return 本次操作返回的中间件基础结果。
     */
    public static MiddlewareBase beforeCompaction(int protectedMessages) {
        return new BeforeMiddleware(protectedMessages);
    }

    /**
     * 在处理后归并压缩。
     *
     * @return 本次操作返回的中间件基础结果。
     */
    public static MiddlewareBase afterCompaction() {
        return new AfterMiddleware();
    }

    /** 受保护开头上下文中间件执行前的中间件钩子，接入对应上下文与观测策略。 */
    private static final class BeforeMiddleware implements MiddlewareBase {
        /** 当前处理段需要保留的开头消息视图。 */
        private final int protectedMessages;

        /**
         * 创建处理前中间件，初始化该组件所需的状态、配置或依赖。
         *
         * @param protectedMessages 当前处理前中间件使用的受保护消息集合，供其处理与状态记录使用。
         * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
         */
        private BeforeMiddleware(int protectedMessages) {
            if (protectedMessages < 0) {
                throw new IllegalArgumentException("protectedMessages must not be negative");
            }
            this.protectedMessages = protectedMessages;
        }

        /**
         * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
         *
         * @return 中间件执行顺序值。
         */
        @Override
        public int order() {
            return 4;
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
            if (protectedMessages == 0 || input.messages() == null) {
                return next.apply(input);
            }
            Split split = Split.from(input.messages(), protectedMessages);
            if (split.head.isEmpty()) {
                if (context != null) {
                    context.put(HEAD_KEY, Split.class, null);
                }
                return next.apply(input);
            }
            if (context != null) {
                context.put(HEAD_KEY, Split.class, split);
            }
            return next.apply(
                    new ReasoningInput(split.withoutHead, input.tools(), input.options()));
        }
    }

    /** 受保护开头上下文中间件执行后的中间件钩子，接入对应上下文与观测策略。 */
    private static final class AfterMiddleware implements MiddlewareBase {
        /**
         * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
         *
         * @return 中间件执行顺序值。
         */
        @Override
        public int order() {
            return -1;
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
            Split split = context == null ? null : context.get(HEAD_KEY, Split.class);
            if (split == null || split.head.isEmpty()) {
                return next.apply(input);
            }
            List<Msg> restored = split.restore(input.messages());
            AgentState state = RuntimeContext.resolveAgentState(context, agent);
            if (state != null) {
                List<Msg> conversation =
                        restored.stream()
                                .filter(message -> message.getRole() != MsgRole.SYSTEM)
                                .toList();
                state.contextMutable().clear();
                state.contextMutable().addAll(conversation);
            }
            return next.apply(new ReasoningInput(restored, input.tools(), input.options()));
        }
    }

    /** 受保护开头上下文中间件内部的切分，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Split {
        /** 系统的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private final List<Msg> system;

        /** 开头的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private final List<Msg> head;

        /** 缺省开头的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private final List<Msg> withoutHead;

        /**
         * 从输入构造切分。
         *
         * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @param protectedMessages 当前切分使用的受保护消息集合，供其处理与状态记录使用。
         * @return 本次操作返回的切分结果。
         */
        private static Split from(List<Msg> messages, int protectedMessages) {
            List<Msg> system = new ArrayList<>();
            List<Msg> conversation = new ArrayList<>();
            for (Msg message : messages) {
                if (message.getRole() == MsgRole.SYSTEM) {
                    system.add(message);
                } else {
                    conversation.add(message);
                }
            }
            int boundary = Math.min(protectedMessages, conversation.size());
            while (boundary < conversation.size()
                    && conversation.get(boundary).getRole() == MsgRole.TOOL) {
                boundary++;
            }
            List<Msg> head = new ArrayList<>(conversation.subList(0, boundary));
            List<Msg> reduced = new ArrayList<>(system);
            reduced.addAll(conversation.subList(boundary, conversation.size()));
            return new Split(system, head, reduced);
        }

        /**
         * 恢复切分。
         *
         * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @return 本次处理得到的结果集合。
         */
        private List<Msg> restore(List<Msg> messages) {
            // Harness 可能在两次压缩钩子之间追加已选子 Agent 目录。恢复受保护的对话消息时，
            // 需要保留该目录。
            List<Msg> currentSystem =
                    messages == null
                            ? List.of()
                            : messages.stream()
                                    .filter(message -> message.getRole() == MsgRole.SYSTEM)
                                    .toList();
            List<Msg> restored = new ArrayList<>(currentSystem.isEmpty() ? system : currentSystem);
            restored.addAll(head);
            if (messages != null) {
                messages.stream()
                        .filter(message -> message.getRole() != MsgRole.SYSTEM)
                        .forEach(restored::add);
            }
            return restored;
        }
    }
}
