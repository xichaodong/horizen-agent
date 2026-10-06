package dev.horizen.agent.common.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 共享 JSON 配置与常用操作，配置在初始化后不再修改。
 */
public final class JsonUtils {
    // 确定性的统一传输格式，不受类路径发现或类加载顺序影响。
    /**
     * 默认的固定取值，用于相应策略和边界判断。
     */
    private static final ObjectMapper DEFAULT =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private JsonUtils() {
    }

    /**
     * 为长期使用的协议编解码器创建独立映射器，修改配置不影响其他编解码器。
     */
    public static ObjectMapper newMapper() {
        return DEFAULT.copy();
    }

    /**
     * 计算或取得本方法声明的结果，供当前JsonUtils处理步骤使用。
     *
     * @return 本次操作返回的对象映射器结果。
     */
    @Deprecated
    public static ObjectMapper mapper() {
        return newMapper();
    }

    /**
     * 计算或取得本方法声明的结果，供当前JsonUtils处理步骤使用。
     *
     * @return 本次操作返回的对象映射器结果。
     */
    @Deprecated
    public static ObjectMapper moduleMapper() {
        return newMapper();
    }

    /**
     * 计算或取得本方法声明的结果，供当前JsonUtils处理步骤使用。
     *
     * @param type 当前操作使用的目标类型或类别。
     * @return 本次操作返回的对象读取器结果。
     */
    public static ObjectReader readerFor(Class<?> type) {
        return DEFAULT.readerFor(type);
    }

    /**
     * 计算或取得本方法声明的结果，供当前JsonUtils处理步骤使用。
     *
     * @return 本次操作返回的对象写入器结果。
     */
    public static ObjectWriter writer() {
        return DEFAULT.writer();
    }

    /**
     * 写入JSON工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    public static String write(Object value) throws JsonProcessingException {
        return DEFAULT.writeValueAsString(value);
    }

    /**
     * 读取Tree。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的JSON节点结果。
     */
    public static JsonNode readTree(String value) throws JsonProcessingException {
        return DEFAULT.readTree(value);
    }

    /**
     * 读取JSON工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param type  当前操作使用的目标类型或类别。
     * @return 本次操作返回的类型参数结果。
     */
    public static <T> T read(String value, Class<T> type) throws JsonProcessingException {
        return DEFAULT.readValue(value, type);
    }

    /**
     * 读取JSON工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param type  当前操作使用的目标类型或类别。
     * @return 本次操作返回的类型参数结果。
     */
    public static <T> T read(String value, TypeReference<T> type) throws JsonProcessingException {
        return DEFAULT.readValue(value, type);
    }

    /**
     * 转换为JSON。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String toJson(Object value) {
        try {
            return write(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot encode JSON value", error);
        }
    }
}
