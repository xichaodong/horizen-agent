package dev.horizen.agent.storage.jdbc.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.json.JsonUtils;

/**
 * JDBC 适配器中按类型区分的不可变请求与可变响应信封。
 */
public final class JdbcInteractionJson {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private JdbcInteractionJson() {
    }

    /**
     * 编码JDBC交互JSON。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("invalid interaction content", error);
        }
    }

    /**
     * 读取JDBC交互JSON。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的对象节点结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static ObjectNode read(String value) {
        try {
            if (JSON.readTree(value) instanceof ObjectNode object) return object;
            throw new IllegalStateException("interaction content must be an object");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invalid stored interaction content", error);
        }
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param object 当前JDBC交互JSON持有的对象对象，供相应处理步骤使用。
     * @param key    当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    public static String text(ObjectNode object, String key) {
        var value = object.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }
}
