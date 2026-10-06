package dev.horizen.agent.storage.jdbc.support;

import org.springframework.dao.EmptyResultDataAccessException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Function;

/**
 * 将数据库类型化行转换为领域值，不执行 SQL。
 */
public final class RowValues {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private RowValues() {
    }

    /**
     * 计算或取得本方法声明的结果，供当前RowValues处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的时间点结果。
     */
    public static Instant instant(Timestamp value) {
        return value.toInstant();
    }

    /**
     * 计算或取得本方法声明的结果，供当前RowValues处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的时间点结果。
     */
    public static Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * 映射One。
     *
     * @param value     待校验、转换或保存的原始值。
     * @param converter 将输入转换为目标结果的函数。
     * @return 本次操作返回的R结果。
     * @throws EmptyResultDataAccessException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static <T, R> R mapOne(T value, Function<? super T, ? extends R> converter) {
        if (value == null) throw new EmptyResultDataAccessException(1);
        return converter.apply(value);
    }
}
