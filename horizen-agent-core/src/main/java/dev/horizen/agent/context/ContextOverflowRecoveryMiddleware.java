package dev.horizen.agent.context;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** 为流式模型调用补充一次上下文超限后的强制压缩与重试。 */
public final class ContextOverflowRecoveryMiddleware implements MiddlewareBase {
    /** 生成上下文摘要的模型，可能与主模型使用同一实例。 */
    private final Model compactionModel;

    /** 上下文超限恢复时采用的强制压缩配置。 */
    private final CompactionConfig emergencyConfig;

    /** 压缩或恢复时需要优先保留的开头消息数量。 */
    private final int protectedHeadMessages;

    /**
     * 创建上下文超限恢复中间件，初始化该组件所需的状态、配置或依赖。
     *
     * @param compactionModel 当前上下文超限恢复中间件持有的压缩模型对象，供相应处理步骤使用。
     * @param emergencyConfig 当前上下文超限恢复中间件持有的emergency配置对象，供相应处理步骤使用。
     */
    public ContextOverflowRecoveryMiddleware(
            Model compactionModel, CompactionConfig emergencyConfig) {
        this(compactionModel, emergencyConfig, 0);
    }

    /**
     * 创建上下文超限恢复中间件，初始化该组件所需的状态、配置或依赖。
     *
     * @param compactionModel 当前上下文超限恢复中间件持有的压缩模型对象，供相应处理步骤使用。
     * @param emergencyConfig 当前上下文超限恢复中间件持有的emergency配置对象，供相应处理步骤使用。
     * @param protectedHeadMessages 当前上下文超限恢复中间件使用的受保护开头消息集合，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ContextOverflowRecoveryMiddleware(
            Model compactionModel, CompactionConfig emergencyConfig, int protectedHeadMessages) {
        if (protectedHeadMessages < 0) {
            throw new IllegalArgumentException("protectedHeadMessages must not be negative");
        }
        this.compactionModel = compactionModel;
        this.emergencyConfig = emergencyConfig;
        this.protectedHeadMessages = protectedHeadMessages;
    }

    /**
     * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
     *
     * @return 中间件执行顺序值。
     */
    @Override
    public int order() {
        return 2;
    }

    /**
     * 拦截模型上下文超限失败，按策略压缩工作上下文并尝试恢复原调用。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input 本次处理的输入。
     * @param next 将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .onErrorResume(
                        error -> {
                            if (!isContextOverflow(error)) {
                                return Flux.error(error);
                            }
                            return recover(agent, context, input)
                                    .flatMapMany(
                                            recovered ->
                                                    Flux.concat(
                                                            Flux.just(
                                                                    recovered.event,
                                                                    new CustomEvent(
                                                                            "horizen.execution_notice",
                                                                            Map.of(
                                                                                    "errorCode",
                                                                                    "MODEL_CONTEXT_OVERFLOW"))),
                                                            next.apply(recovered.input)))
                                    .onErrorResume(
                                            recoveryError ->
                                                    Flux.concat(
                                                            Flux.just(
                                                                    failureEvent(
                                                                            input.messages(),
                                                                            recoveryError)),
                                                            Flux.error(recoveryError)));
                        });
    }

    /**
     * 从超限请求构造可再次调用模型的工作上下文，保持工具调用与结果的边界。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input 本次处理的输入。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private Mono<RecoveredCall> recover(Agent agent, RuntimeContext context, ModelCallInput input) {
        List<Msg> original = input.messages() == null ? List.of() : input.messages();
        List<Msg> system =
                original.stream().filter(message -> message.getRole() == MsgRole.SYSTEM).toList();
        List<Msg> conversation =
                new ArrayList<>(
                        original.stream()
                                .filter(message -> message.getRole() != MsgRole.SYSTEM)
                                .toList());
        int headBoundary = Math.min(protectedHeadMessages, conversation.size());
        while (headBoundary < conversation.size()
                && conversation.get(headBoundary).getRole() == MsgRole.TOOL) {
            headBoundary++;
        }
        List<Msg> protectedHead = new ArrayList<>(conversation.subList(0, headBoundary));
        List<Msg> compactable =
                new ArrayList<>(conversation.subList(headBoundary, conversation.size()));
        if (compactable.isEmpty()) {
            return Mono.error(
                    new IllegalStateException(
                            "Context overflow recovery has no conversation to compact"));
        }
        WorkspaceManager workspace = context == null ? null : context.get(WorkspaceManager.class);
        MemoryFlushManager flushManager = new MemoryFlushManager(workspace, compactionModel);
        ConversationCompactor compactor = new ConversationCompactor(compactionModel, flushManager);
        long startedAt = System.nanoTime();
        int tokensBefore = TokenCounterUtil.calculateToken(original);
        return compactor
                .compactIfNeeded(
                        context == null ? RuntimeContext.empty() : context,
                        compactable,
                        emergencyConfig,
                        agent.getName(),
                        context == null || context.getSessionId() == null
                                ? "default"
                                : context.getSessionId())
                .flatMap(
                        result ->
                                recoveredInput(
                                        agent,
                                        context,
                                        input,
                                        system,
                                        protectedHead,
                                        result,
                                        tokensBefore,
                                        startedAt));
    }

    /**
     * 用恢复后的上下文重建本次模型输入。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input 本次处理的输入。
     * @param system 系统的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param protectedHead 受保护开头的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param result 本次处理已有的结果。
     * @param tokensBefore 当前上下文超限恢复中间件使用的token处理前，供其处理与状态记录使用。
     * @param startedAt 当前执行或执行段的开始时间。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private Mono<RecoveredCall> recoveredInput(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            List<Msg> system,
            List<Msg> protectedHead,
            Optional<List<Msg>> result,
            int tokensBefore,
            long startedAt) {
        if (result.isEmpty()) {
            return Mono.error(
                    new IllegalStateException(
                            "Context overflow recovery could not find a safe compaction boundary"));
        }
        List<Msg> compacted = result.get();
        Msg summary =
                compacted.stream()
                        .filter(
                                message ->
                                        ConversationCompactor.SUMMARY_MSG_NAME.equals(
                                                message.getName()))
                        .findFirst()
                        .orElse(null);
        if (summary == null || CompactionSummary.failed(summary)) {
            return Mono.error(
                    new IllegalStateException("Context overflow recovery summary failed"));
        }
        AgentState state = RuntimeContext.resolveAgentState(context, agent);
        List<Msg> recoveredConversation = new ArrayList<>(protectedHead);
        recoveredConversation.addAll(compacted);
        if (state != null) {
            state.contextMutable().clear();
            state.contextMutable().addAll(recoveredConversation);
        }
        List<Msg> retryMessages = new ArrayList<>(system);
        retryMessages.addAll(recoveredConversation);
        int tokensAfter = TokenCounterUtil.calculateToken(retryMessages);
        CustomEvent event =
                new CustomEvent(
                        ContextCompactionTelemetry.EVENT_NAME,
                        Map.of(
                                "reason", "context_overflow",
                                "protectedHeadMessages", protectedHead.size(),
                                "messagesBefore", input.messages().size(),
                                "messagesAfter", retryMessages.size(),
                                "estimatedTokensBefore", tokensBefore,
                                "estimatedTokensAfter", tokensAfter,
                                "estimatedTokensReclaimed", Math.max(0, tokensBefore - tokensAfter),
                                "durationMs",
                                        Math.max(0, (System.nanoTime() - startedAt) / 1_000_000)));
        return Mono.just(
                new RecoveredCall(
                        new ModelCallInput(
                                retryMessages, input.tools(), input.options(), input.model()),
                        event));
    }

    /**
     * 生成压缩或恢复失败的宿主可观察事件。
     *
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次操作返回的Custom事件结果。
     */
    private static CustomEvent failureEvent(List<Msg> messages, Throwable error) {
        return new CustomEvent(
                ContextCompactionTelemetry.FAILURE_EVENT_NAME,
                Map.of(
                        "reason",
                        "context_overflow",
                        "messagesBefore",
                        messages == null ? 0 : messages.size(),
                        "estimatedTokensBefore",
                        TokenCounterUtil.calculateToken(messages),
                        "failureCode",
                        error.getClass().getSimpleName()));
    }

    /**
     * 识别模型服务报告的上下文容量超限，不把所有模型失败都当作超限。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean isContextOverflow(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("context_length_exceeded")
                        || lower.contains("context length")
                        || lower.contains("maximum context")
                        || lower.contains("token limit")
                        || lower.contains("too many tokens")
                        || lower.contains("exceeds the model's maximum")
                        || lower.contains("reduce the length")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /** 上下文超限恢复中间件内部的恢复后的调用，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class RecoveredCall {
        /** 当前操作的输入数据，格式由所属命令、协议或工具定义。 */
        private final ModelCallInput input;

        /** 当前记录保存的执行事件或已转换的发送事件。 */
        private final CustomEvent event;
    }
}
