package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.context.CompactionPerTurnLimitMiddleware;
import dev.horizen.agent.context.ContextOverflowRecoveryMiddleware;
import dev.horizen.agent.context.ProtectedHeadContextMiddleware;
import dev.horizen.agent.web.config.ContextPolicyValidator;
import dev.horizen.agent.web.config.ContextProperties;

import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;

import lombok.RequiredArgsConstructor;

/**
 * 将已校验的宿主策略映射为当前固定版本的 AgentScope 能力。
 */
@RequiredArgsConstructor
public final class ContextRuntimeConfigurer {
    /**
     * 宿主绑定的配置对象，供组件组装与策略校验使用。
     */
    private final ContextProperties properties;

    /**
     * 将宿主配置映射到 AgentScope 原生上下文能力。
     */
    public void apply(HarnessAgent.Builder builder) {
        apply(builder, null, null);
    }

    /**
     * 将策略应用到 AgentScope，并让常规压缩和超限恢复共用同一个压缩模型。
     */
    public void apply(HarnessAgent.Builder builder, Model compactionModel) {
        apply(builder, compactionModel, compactionModel);
    }

    /**
     * 独立压缩模型只负责摘要；触发阈值仍根据主模型窗口计算。
     */
    public void apply(HarnessAgent.Builder builder, Model primaryModel, Model compactionModel) {
        ContextPolicyValidator.validate(properties);
        builder.maxContextTokens(properties.getWorkspaceContextMaxTokens());
        if (properties.isCompactionEnabled()) {
            builder.middleware(
                    ProtectedHeadContextMiddleware.beforeCompaction(
                            properties.getProtectHeadMessages()));
            builder.middleware(ProtectedHeadContextMiddleware.afterCompaction());
            builder.middleware(
                    CompactionPerTurnLimitMiddleware.beforeCompaction(
                            properties.getMaxCompactionsPerTurn()));
            builder.middleware(CompactionPerTurnLimitMiddleware.afterCompaction());
            builder.compaction(toCompactionConfig(primaryModel, compactionModel));
            if (properties.isOverflowRecoveryEnabled() && compactionModel != null) {
                builder.middleware(
                        new ContextOverflowRecoveryMiddleware(
                                compactionModel,
                                toEmergencyCompactionConfig(compactionModel),
                                properties.getProtectHeadMessages()));
            }
        } else {
            builder.disableCompaction();
        }
        if (properties.isToolResultEvictionEnabled()) {
            builder.toolResultEviction(
                    ToolResultEvictionConfig.builder()
                            .maxResultChars(properties.getToolResultEvictionMaxChars())
                            .previewChars(properties.getToolResultEvictionPreviewChars())
                            .evictionPath(properties.getToolResultEvictionPath())
                            .build());
        } else {
            // 沙箱按 Turn 销毁时，不能把后续上下文指向沙箱内的临时文件。
            builder.disableToolResultEviction();
        }
    }

    /**
     * 转换为压缩配置。
     *
     * @return 本次操作返回的压缩配置结果。
     */
    public CompactionConfig toCompactionConfig() {
        return toCompactionConfig(null);
    }

    /**
     * 转换为压缩配置。
     *
     * @param compactionModel 当前上下文运行时配置器持有的压缩模型对象，供相应处理步骤使用。
     * @return 本次操作返回的压缩配置结果。
     */
    public CompactionConfig toCompactionConfig(Model compactionModel) {
        return toCompactionConfig(compactionModel, compactionModel);
    }

    /**
     * 转换为压缩配置。
     *
     * @param primaryModel    当前上下文运行时配置器持有的primary模型对象，供相应处理步骤使用。
     * @param compactionModel 当前上下文运行时配置器持有的压缩模型对象，供相应处理步骤使用。
     * @return 本次操作返回的压缩配置结果。
     */
    public CompactionConfig toCompactionConfig(Model primaryModel, Model compactionModel) {
        ContextPolicyValidator.validate(properties);
        int effectiveTriggerTokens = properties.getTriggerTokens();
        int effectiveKeepTokens = properties.getKeepTokens();
        int primaryWindow = primaryModel == null ? 0 : primaryModel.getContextWindowSize();
        if (primaryWindow > 0) {
            if (effectiveTriggerTokens == 0) {
                effectiveTriggerTokens =
                        Math.max(1, primaryWindow - properties.getReservedTokens());
                if (effectiveTriggerTokens <= 1) {
                    effectiveTriggerTokens = Math.max(1, primaryWindow / 2);
                }
            }
            if (effectiveKeepTokens == -1) {
                int usable = Math.max(1, primaryWindow - properties.getReservedTokens());
                effectiveKeepTokens =
                        Math.min(
                                properties.getKeepTokensMax(),
                                Math.max(
                                        properties.getKeepTokensMin(),
                                        (int) (usable * properties.getKeepTokensRatio())));
            }
        }
        CompactionConfig.Builder builder =
                CompactionConfig.builder()
                        .triggerMessages(properties.getTriggerMessages())
                        .triggerTokens(effectiveTriggerTokens)
                        .reserved(properties.getReservedTokens())
                        .keepMessages(properties.getKeepMessages())
                        .keepTokens(effectiveKeepTokens)
                        .keepTokensMin(properties.getKeepTokensMin())
                        .keepTokensMax(properties.getKeepTokensMax())
                        .keepTokensRatio(properties.getKeepTokensRatio())
                        .flushBeforeCompact(properties.isFlushBeforeCompact())
                        .offloadBeforeCompact(properties.isOffloadBeforeCompact())
                        .summaryPrompt(summaryPrompt())
                        .prune(
                                properties.isPruneToolResultsEnabled()
                                        ? CompactionConfig.PruneConfig.builder()
                                        .protectTokens(properties.getPruneProtectTokens())
                                        .minimumTokens(properties.getPruneMinimumTokens())
                                        .maxOutputChars(properties.getPruneMaxOutputChars())
                                        .excludedTools(properties.getPruneExcludedTools())
                                        .build()
                                        : null);
        if (compactionModel != null) {
            builder.model(compactionModel);
        }
        if (properties.isTruncateArgumentsEnabled()) {
            builder.truncateArgs(
                    CompactionConfig.TruncateArgsConfig.builder()
                            .triggerMessages(properties.getTruncateArgumentsTriggerMessages())
                            .triggerTokens(properties.getTruncateArgumentsTriggerTokens())
                            .keepMessages(properties.getTruncateArgumentsKeepMessages())
                            .keepTokens(properties.getTruncateArgumentsKeepTokens())
                            .maxArgLength(properties.getTruncateArgumentsMaxLength())
                            .build());
        }
        return builder.build();
    }

    /**
     * 转换为Emergency压缩配置。
     *
     * @param compactionModel 当前上下文运行时配置器持有的压缩模型对象，供相应处理步骤使用。
     * @return 本次操作返回的压缩配置结果。
     */
    public CompactionConfig toEmergencyCompactionConfig(Model compactionModel) {
        ContextPolicyValidator.validate(properties);
        CompactionConfig.Builder builder =
                CompactionConfig.builder()
                        .triggerMessages(1)
                        .triggerTokens(1)
                        .keepMessages(properties.getOverflowKeepMessages())
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .summaryPrompt(summaryPrompt())
                        .prune(
                                properties.isPruneToolResultsEnabled()
                                        ? CompactionConfig.PruneConfig.builder()
                                        .protectTokens(properties.getPruneProtectTokens())
                                        .minimumTokens(properties.getPruneMinimumTokens())
                                        .maxOutputChars(properties.getPruneMaxOutputChars())
                                        .excludedTools(properties.getPruneExcludedTools())
                                        .build()
                                        : null);
        if (compactionModel != null) {
            builder.model(compactionModel);
        }
        return builder.build();
    }

    /**
     * 生成当前操作所需的summaryPrompt文本，供调用方继续处理。
     *
     * @return 本次处理生成或读取的文本。
     */
    private String summaryPrompt() {
        String lengthInstruction =
                properties.getCompressionMaxOutputTokens() > 0
                        ? "总长度不得超过 " + properties.getCompressionMaxOutputTokens() + " Token。"
                        : "在信息完整的前提下尽量精炼。";
        return """
                你是会话上下文压缩器。请把下面历史整理成可供 Agent 继续执行的短摘要。

                只保留：
                1. 用户目标、硬性约束和已确认决策；
                2. 已完成动作、关键结论、重要文件或资源；
                3. 仍有效的工具结果和错误原因；
                4. 未完成事项与下一步。

                删除重复叙述、寒暄、已失效的中间过程和可重新获取的冗长原始输出。
                不推测，不补写未发生的事实；遇到凭据时写成 [REDACTED]。
                %s仅输出摘要正文。

                <messages>
                {messages}
                </messages>
                """
                .formatted(lengthInstruction);
    }
}
