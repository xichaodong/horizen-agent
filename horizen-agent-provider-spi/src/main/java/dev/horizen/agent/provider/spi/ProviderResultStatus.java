package dev.horizen.agent.provider.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Provider 协议约定的工具执行状态，不等同于模型输出流是否结束。
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public enum ProviderResultStatus implements WireValue {
    /**
     * Provider 报告当前工具调用成功。
     */
    SUCCESS("success"),
    /**
     * Provider 或对应处理器报告失败。
     */
    ERROR("error"),
    /**
     * 用户已拒绝执行原工具调用。
     */
    DENIED("denied"),
    /**
     * 执行或交互已取消，不再继续原处理。
     */
    CANCELLED("cancelled");

    /**
     * 对外协议使用的固定文本值，与 Java 枚举成员名称分开维护。
     */
    @Getter(onMethod_ = {@Override, @JsonValue})
    @Accessors(fluent = true)
    private final String wireValue;

    /**
     * 从输入构造协议传输值。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的提供方结果状态结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @JsonCreator
    public static ProviderResultStatus fromWireValue(String value) {
        for (ProviderResultStatus item : values())
            if (item.wireValue.equalsIgnoreCase(value)) return item;
        throw new IllegalArgumentException("unknown provider result status: " + value);
    }
}
