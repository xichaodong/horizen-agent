package dev.horizen.agent.provider.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 工具目录声明的风险级别，供宿主治理策略使用。 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public enum RiskLevel implements WireValue {
    /** 低风险工具操作，由宿主治理策略决定执行边界。 */
    LOW("low"),
    /** 中等风险工具操作，由宿主治理策略决定执行边界。 */
    MEDIUM("medium"),
    /** 高风险工具操作，需要结合审批和访问范围治理。 */
    HIGH("high"),
    /** 最高风险类别，宿主应按明确授权策略处理。 */
    CRITICAL("critical");

    /** 对外协议使用的固定文本值，与 Java 枚举成员名称分开维护。 */
    @Getter(onMethod_ = {@Override, @JsonValue})
    @Accessors(fluent = true)
    private final String wireValue;

    /**
     * 从输入构造协议传输值。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的风险级别结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @JsonCreator
    public static RiskLevel fromWireValue(String value) {
        for (RiskLevel item : values()) if (item.wireValue.equalsIgnoreCase(value)) return item;
        throw new IllegalArgumentException("unknown risk level: " + value);
    }
}
