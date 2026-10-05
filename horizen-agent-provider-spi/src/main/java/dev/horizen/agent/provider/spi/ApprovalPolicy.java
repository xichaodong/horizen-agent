package dev.horizen.agent.provider.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 工具执行前是否需要人工确认的策略。 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public enum ApprovalPolicy implements WireValue {
    /** 当前策略不要求人工确认。 */
    NONE("none"),
    /** 当前策略要求取得人工确认后执行。 */
    REQUIRED("required");

    /** 对外协议使用的固定文本值，与 Java 枚举成员名称分开维护。 */
    @Getter(onMethod_ = {@Override, @JsonValue})
    @Accessors(fluent = true)
    private final String wireValue;

    /**
     * 从输入构造协议传输值。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的审批策略结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @JsonCreator
    public static ApprovalPolicy fromWireValue(String value) {
        for (ApprovalPolicy item : values())
            if (item.wireValue.equalsIgnoreCase(value)) return item;
        throw new IllegalArgumentException("unknown approval policy: " + value);
    }
}
