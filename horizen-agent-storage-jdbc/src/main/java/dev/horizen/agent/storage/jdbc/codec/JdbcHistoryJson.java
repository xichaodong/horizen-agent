package dev.horizen.agent.storage.jdbc.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.json.JsonUtils;

/** JDBC 历史记录信封；领域仓储契约不依赖 JSON。 */
public final class JdbcHistoryJson {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    public static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private JdbcHistoryJson() {}

    /**
     * 计算或取得本方法声明的结果，供当前JdbcHistoryJson处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的对象节点结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static ObjectNode object(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalStateException("history payload must be an object");
            }
            return object;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invalid history payload", error);
        }
    }

    /**
     * 编码JDBC历史JSON。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("invalid history content", error);
        }
    }
}
