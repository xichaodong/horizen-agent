package dev.horizen.agent.provider.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 工具目录的访问范围与版本定位信息。
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public enum CatalogScope implements WireValue {
    /**
     * 仅查询工具目录注册信息，不代表实际业务调用已经授权。
     */
    REGISTRATION("registration"),
    /**
     * 与具体执行关联的目录或调用范围。
     */
    TURN("turn");

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
     * @return 本次操作返回的目录作用域结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @JsonCreator
    public static CatalogScope fromWireValue(String value) {
        for (CatalogScope item : values()) if (item.wireValue.equalsIgnoreCase(value)) return item;
        throw new IllegalArgumentException("unknown catalog scope: " + value);
    }
}
