package dev.horizen.agent.storage.jdbc.support;

import java.util.LinkedHashMap;
import java.util.Map;

/** 为固定 Mapper 语句按位置绑定参数，SQL 不能由调用者提供。 */
public final class SqlParams {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private SqlParams() {}

    /**
     * 计算或取得本方法声明的结果，供当前SqlParams处理步骤使用。
     *
     * @param values 本次批量处理的值集合。
     * @return 按返回类型约定组织的结果映射。
     */
    public static Map<String, Object> values(Object... values) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) parameters.put("p" + i, values[i]);
        return parameters;
    }
}
