package dev.horizen.agent.context;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 在 AgentScope 压缩中间件前后采样，并发出不含对话正文的压缩事件。 */
public final class ContextCompactionTelemetry {
    /** 事件名称使用的固定标识或协议文本。 */
    public static final String EVENT_NAME = "context_compacted";

    /** 失败事件名称使用的固定标识或协议文本。 */
    public static final String FAILURE_EVENT_NAME = "context_compaction_failed";

    /** 快照键的固定取值，用于相应策略和边界判断。 */
    private static final String SNAPSHOT_KEY = ContextCompactionTelemetry.class.getName();

    /** 数量键的固定取值，用于相应策略和边界判断。 */
    private static final String COUNT_KEY = ContextCompactionTelemetry.class.getName() + ".count";

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private ContextCompactionTelemetry() {}

    /**
     * 在处理前准备压缩。
     *
     * @return 本次操作返回的中间件基础结果。
     */
    public static MiddlewareBase beforeCompaction() {
        return new BeforeMiddleware();
    }

    /**
     * 在处理后归并压缩。
     *
     * @return 本次操作返回的中间件基础结果。
     */
    public static MiddlewareBase afterCompaction() {
        return afterCompaction(false);
    }

    /**
     * 在处理后归并压缩。
     *
     * @param abortOnSummaryFailure abort响应摘要失败的状态标记，用于选择当前组件的处理路径。
     * @return 本次操作返回的中间件基础结果。
     */
    public static MiddlewareBase afterCompaction(boolean abortOnSummaryFailure) {
        return new AfterMiddleware(abortOnSummaryFailure);
    }

    /** 上下文压缩观测执行前的中间件钩子，接入对应上下文与观测策略。 */
    private static final class BeforeMiddleware implements MiddlewareBase {
        /**
         * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
         *
         * @return 中间件执行顺序值。
         */
        @Override
        public int order() {
            return 5;
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
            if (context != null) {
                context.put(SNAPSHOT_KEY, Snapshot.class, Snapshot.from(input.messages()));
            }
            return next.apply(input);
        }
    }

    /** 上下文压缩观测执行后的中间件钩子，接入对应上下文与观测策略。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class AfterMiddleware implements MiddlewareBase {
        /** abort响应摘要失败的状态标记，用于选择当前组件的处理路径。 */
        private final boolean abortOnSummaryFailure;

        /**
         * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
         *
         * @return 中间件执行顺序值。
         */
        @Override
        public int order() {
            return -2;
        }

        /**
         * 响应模型推理。
         * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
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
            Snapshot before = context == null ? null : context.get(SNAPSHOT_KEY, Snapshot.class);
            Snapshot after = Snapshot.from(input.messages());
            if (before != null && after.summaryFailed && !before.sameAs(after)) {
                incrementCompactionCount(context);
                CustomEvent event =
                        new CustomEvent(
                                FAILURE_EVENT_NAME,
                                Map.of(
                                        "messagesBefore",
                                        before.messageCount,
                                        "estimatedTokensBefore",
                                        before.estimatedTokens,
                                        "failureCode",
                                        "COMPACTION_SUMMARY_FAILED",
                                        "durationMs",
                                        Math.max(
                                                0,
                                                (System.nanoTime() - before.startedAtNanos)
                                                        / 1_000_000)));
                ReasoningInput restored =
                        restoreOriginalContext(agent, context, before.messages, input);
                if (abortOnSummaryFailure) {
                    return Flux.concat(
                            Flux.just(event),
                            Flux.error(
                                    new IllegalStateException(
                                            "Context compaction summary failed")));
                }
                return Flux.concat(Flux.just(event), next.apply(restored));
            }
            if (before == null || !after.hasSummary || before.sameAs(after)) {
                return next.apply(input);
            }
            incrementCompactionCount(context);
            CustomEvent event =
                    new CustomEvent(
                            EVENT_NAME,
                            Map.of(
                                    "messagesBefore",
                                    before.messageCount,
                                    "messagesAfter",
                                    after.messageCount,
                                    "estimatedTokensBefore",
                                    before.estimatedTokens,
                                    "estimatedTokensAfter",
                                    after.estimatedTokens,
                                    "estimatedTokensReclaimed",
                                    Math.max(0, before.estimatedTokens - after.estimatedTokens),
                                    "durationMs",
                                    Math.max(
                                            0,
                                            (System.nanoTime() - before.startedAtNanos)
                                                    / 1_000_000)));
            return Flux.concat(Flux.just(event), next.apply(input));
        }

        /**
         * 恢复原始上下文。
         *
         * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
         * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
         * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @param input 本次处理的输入。
         * @return 本次操作返回的模型推理输入结果。
         */
        private static ReasoningInput restoreOriginalContext(
                Agent agent, RuntimeContext context, List<Msg> messages, ReasoningInput input) {
            // 保留期间其他 Harness 中间件添加的系统指令。
            List<Msg> restored =
                    new ArrayList<>(
                            input.messages().stream()
                                    .filter(message -> message.getRole() == MsgRole.SYSTEM)
                                    .toList());
            messages.stream()
                    .filter(message -> message.getRole() != MsgRole.SYSTEM)
                    .forEach(restored::add);
            AgentState state = RuntimeContext.resolveAgentState(context, agent);
            if (state != null) {
                state.contextMutable().clear();
                restored.stream()
                        .filter(message -> message.getRole() != MsgRole.SYSTEM)
                        .forEach(state.contextMutable()::add);
            }
            return new ReasoningInput(restored, input.tools(), input.options());
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前ContextCompactionTelemetry处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的整数结果。
     */
    static int compactionCount(RuntimeContext context) {
        Integer count = context == null ? null : context.get(COUNT_KEY, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 完成当前操作的incrementCompactionCount步骤，按实现更新相应状态或依赖。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     */
    private static void incrementCompactionCount(RuntimeContext context) {
        if (context != null) {
            context.put(COUNT_KEY, Integer.class, compactionCount(context) + 1);
        }
    }

    /** 压缩前后消息与 token 估算的观测快照。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Snapshot {
        /** 消息的数量，供运行统计或容量控制使用。 */
        private final int messageCount;

        /** estimated的 token 数量或预算。 */
        private final int estimatedTokens;

        /** 本次上下文摘要的指纹，用于识别摘要是否已经改变。 */
        private final int summaryFingerprint;

        /** 是否存在摘要的状态标记，用于选择当前组件的处理路径。 */
        private final boolean hasSummary;

        /** 摘要失败的状态标记，用于选择当前组件的处理路径。 */
        private final boolean summaryFailed;

        /** 使用单调时钟记录的处理起点，单位为纳秒，仅用于计算耗时。 */
        private final long startedAtNanos;

        /** 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private final List<Msg> messages;

        /**
         * 从输入构造快照。
         * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
         *
         * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @return 本次操作返回的快照结果。
         */
        private static Snapshot from(List<Msg> messages) {
            List<Msg> safe = messages == null ? List.of() : messages;
            Msg summary =
                    safe.stream()
                            .filter(
                                    message ->
                                            ConversationCompactor.SUMMARY_MSG_NAME.equals(
                                                    message.getName()))
                            .findFirst()
                            .orElse(null);
            return new Snapshot(
                    safe.size(),
                    TokenCounterUtil.calculateToken(safe),
                    summary == null ? 0 : summary.getTextContent().hashCode(),
                    summary != null,
                    CompactionSummary.failed(summary),
                    System.nanoTime(),
                    List.copyOf(safe));
        }

        /**
         * 检查sameAs对应的条件，供调用方选择后续处理分支。
         *
         * @param other 参与比较或合并的另一个对象。
         * @return 本次检查是否通过或本次更新是否成功。
         */
        private boolean sameAs(Snapshot other) {
            return messageCount == other.messageCount
                    && estimatedTokens == other.estimatedTokens
                    && summaryFingerprint == other.summaryFingerprint;
        }
    }
}
