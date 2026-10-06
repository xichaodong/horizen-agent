package dev.horizen.agent.web.config;

/**
 * 跨字段策略校验，不依赖 AgentScope 组装。
 */
public final class ContextPolicyValidator {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ContextPolicyValidator() {
    }

    /**
     * 校验当前上下文策略校验器的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void validate(ContextProperties properties) {
        requireNonNegative("modelContextWindowTokens", properties.getModelContextWindowTokens());
        requireNonNegative(
                "compressionModelContextWindowTokens",
                properties.getCompressionModelContextWindowTokens());
        requireNonNegative(
                "compressionMaxOutputTokens", properties.getCompressionMaxOutputTokens());
        if (properties.getCompressionTemperature() < 0
                || properties.getCompressionTemperature() > 2) {
            throw new IllegalArgumentException("compressionTemperature must be in [0, 2]");
        }
        if (properties.getCompressionTimeout() == null
                || properties.getCompressionTimeout().isZero()
                || properties.getCompressionTimeout().isNegative()) {
            throw new IllegalArgumentException("compressionTimeout must be positive");
        }
        requirePositive("compressionMaxAttempts", properties.getCompressionMaxAttempts());
        requirePositive("workspaceContextMaxTokens", properties.getWorkspaceContextMaxTokens());
        requireNonNegative("protectHeadMessages", properties.getProtectHeadMessages());
        requirePositive("maxCompactionsPerTurn", properties.getMaxCompactionsPerTurn());
        requireNonNegative("triggerMessages", properties.getTriggerMessages());
        requireNonNegative("triggerTokens", properties.getTriggerTokens());
        requirePositive("reservedTokens", properties.getReservedTokens());
        requirePositive("keepMessages", properties.getKeepMessages());
        if (properties.getKeepTokens() < -1) {
            throw new IllegalArgumentException("keepTokens must be -1, 0, or positive");
        }
        requirePositive("keepTokensMin", properties.getKeepTokensMin());
        requirePositive("keepTokensMax", properties.getKeepTokensMax());
        if (properties.getKeepTokensMin() > properties.getKeepTokensMax()) {
            throw new IllegalArgumentException("keepTokensMin must not exceed keepTokensMax");
        }
        if (properties.getKeepTokensRatio() <= 0 || properties.getKeepTokensRatio() > 1) {
            throw new IllegalArgumentException("keepTokensRatio must be in (0, 1]");
        }
        requireNonNegative(
                "truncateArgumentsTriggerMessages",
                properties.getTruncateArgumentsTriggerMessages());
        requireNonNegative(
                "truncateArgumentsTriggerTokens", properties.getTruncateArgumentsTriggerTokens());
        requirePositive(
                "truncateArgumentsKeepMessages", properties.getTruncateArgumentsKeepMessages());
        requireNonNegative(
                "truncateArgumentsKeepTokens", properties.getTruncateArgumentsKeepTokens());
        requirePositive("truncateArgumentsMaxLength", properties.getTruncateArgumentsMaxLength());
        requireNonNegative("pruneProtectTokens", properties.getPruneProtectTokens());
        requireNonNegative("pruneMinimumTokens", properties.getPruneMinimumTokens());
        requirePositive("pruneMaxOutputChars", properties.getPruneMaxOutputChars());
        if (properties.getPruneExcludedTools() == null) {
            throw new IllegalArgumentException("pruneExcludedTools must not be null");
        }
        requirePositive("toolResultEvictionMaxChars", properties.getToolResultEvictionMaxChars());
        requirePositive(
                "toolResultEvictionPreviewChars", properties.getToolResultEvictionPreviewChars());
        if (properties.getToolResultEvictionPreviewChars()
                >= properties.getToolResultEvictionMaxChars() / 2) {
            throw new IllegalArgumentException(
                    "toolResultEvictionPreviewChars must leave room for eviction");
        }
        if (properties.getToolResultEvictionPath() == null
                || properties.getToolResultEvictionPath().isBlank()) {
            throw new IllegalArgumentException("toolResultEvictionPath must not be blank");
        }
        requirePositive("overflowKeepMessages", properties.getOverflowKeepMessages());
    }

    /**
     * 取得并校验正值。
     *
     * @param name  需要定位或处理的名称。
     * @param value 待校验、转换或保存的原始值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * 取得并校验非Negative。
     *
     * @param name  需要定位或处理的名称。
     * @param value 待校验、转换或保存的原始值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
