package dev.horizen.agent.tool.adapter;

import dev.horizen.agent.provider.spi.ApprovalPolicy;
import dev.horizen.agent.provider.spi.RiskLevel;
import dev.horizen.agent.provider.spi.ToolContract;
import dev.horizen.agent.provider.spi.ToolGroupContract;

/**
 * 轻量 Provider SPI 与运行时定义之间的唯一转换入口。
 */
public final class ProviderContractMapper {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ProviderContractMapper() {
    }

    /**
     * 转换为定义。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的工具定义结果。
     */
    public static ToolDefinition toDefinition(ToolContract value) {
        value.validate();
        ToolGroupContract group = value.getGroup();
        return new ToolDefinition(
                value.getName(),
                value.getDescription(),
                value.getInputSchema(),
                value.isReadOnly(),
                value.getRiskLevel().wireValue(),
                value.getTimeoutSeconds(),
                value.isIdempotent(),
                value.isConcurrencySafe(),
                value.isSupportsCancellation(),
                value.getApprovalPolicy().wireValue(),
                new ToolGroupDefinition(
                        group.getId(),
                        group.getDescription(),
                        group.isActiveByDefault(),
                        group.getActivateOnSkill()));
    }

    /**
     * 转换为契约。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的工具契约结果。
     */
    public static ToolContract toContract(ToolDefinition value) {
        ToolGroupDefinition group = value.getGroup();
        return new ToolContract(
                value.getName(),
                value.getDescription(),
                value.getInputSchema(),
                value.isReadOnly(),
                risk(value.getRiskLevel()),
                value.getTimeoutSeconds(),
                value.isIdempotent(),
                value.isConcurrencySafe(),
                value.isSupportsCancellation(),
                ApprovalPolicy.fromWireValue(value.getApprovalPolicy()),
                new ToolGroupContract(
                        group.getId(),
                        group.getDescription(),
                        group.isActiveByDefault(),
                        group.getActivateOnSkill()))
                .validate();
    }

    /**
     * 计算或取得本方法声明的结果，供当前ProviderContractMapper处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的风险级别结果。
     */
    private static RiskLevel risk(String value) {
        if (value == null || value.isBlank()) return RiskLevel.MEDIUM;
        return RiskLevel.fromWireValue(value);
    }
}
