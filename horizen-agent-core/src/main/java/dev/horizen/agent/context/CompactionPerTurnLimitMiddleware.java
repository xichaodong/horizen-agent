package dev.horizen.agent.context;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 限制一次 Agent 调用中的普通阈值压缩次数，避免每个工具迭代都重复摘要。
 */
public final class CompactionPerTurnLimitMiddleware {
    /**
     * HIDDEN输入键的固定取值，用于相应策略和边界判断。
     */
    private static final String HIDDEN_INPUT_KEY =
            CompactionPerTurnLimitMiddleware.class.getName() + ".hiddenInput";

    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private CompactionPerTurnLimitMiddleware() {
    }

    /**
     * 在处理前准备压缩。
     *
     * @param maximumCompactions 当前压缩每项执行上限中间件使用的最大Compactions，供其处理与状态记录使用。
     * @return 本次操作返回的中间件基础结果。
     */
    public static MiddlewareBase beforeCompaction(int maximumCompactions) {
        return new BeforeMiddleware(maximumCompactions);
    }

    /**
     * 在处理后归并压缩。
     *
     * @return 本次操作返回的中间件基础结果。
     */
    public static MiddlewareBase afterCompaction() {
        return new AfterMiddleware();
    }

    /**
     * 压缩每项执行上限中间件执行前的中间件钩子，接入对应上下文与观测策略。
     */
    private static final class BeforeMiddleware implements MiddlewareBase {
        /**
         * 单次执行允许发生的普通上下文压缩次数上限。
         */
        private final int maximumCompactions;

        /**
         * 创建处理前中间件，初始化该组件所需的状态、配置或依赖。
         *
         * @param maximumCompactions 当前处理前中间件使用的最大Compactions，供其处理与状态记录使用。
         * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
         */
        private BeforeMiddleware(int maximumCompactions) {
            if (maximumCompactions < 1) {
                throw new IllegalArgumentException("maximumCompactions must be positive");
            }
            this.maximumCompactions = maximumCompactions;
        }

        /**
         * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
         *
         * @return 中间件执行顺序值。
         */
        @Override
        public int order() {
            return 3;
        }

        /**
         * 响应模型推理。
         *
         * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
         * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
         * @param input   本次处理的输入。
         * @param next    将输入转换为目标结果的函数。
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext context,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            if (context == null
                    || ContextCompactionTelemetry.compactionCount(context) < maximumCompactions) {
                return next.apply(input);
            }
            context.put(HIDDEN_INPUT_KEY, ReasoningInput.class, input);
            List<Msg> systemOnly =
                    input.messages() == null
                            ? List.of()
                            : input.messages().stream()
                            .filter(message -> message.getRole() == MsgRole.SYSTEM)
                            .toList();
            return next.apply(new ReasoningInput(systemOnly, input.tools(), input.options()));
        }
    }

    /**
     * 压缩每项执行上限中间件执行后的中间件钩子，接入对应上下文与观测策略。
     */
    private static final class AfterMiddleware implements MiddlewareBase {
        /**
         * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
         *
         * @return 中间件执行顺序值。
         */
        @Override
        public int order() {
            return 0;
        }

        /**
         * 响应模型推理。
         *
         * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
         * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
         * @param input   本次处理的输入。
         * @param next    将输入转换为目标结果的函数。
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext context,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            ReasoningInput hidden =
                    context == null ? null : context.get(HIDDEN_INPUT_KEY, ReasoningInput.class);
            if (hidden == null) {
                return next.apply(input);
            }
            context.put(HIDDEN_INPUT_KEY, ReasoningInput.class, null);
            return next.apply(hidden);
        }
    }
}
