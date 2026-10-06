package dev.horizen.agent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 把任意值转换为 JSON 兼容结构，并遮蔽常见的敏感字段。
 */
public final class TraceDataSanitizer {
    /**
     * 密钥键的固定取值，用于相应策略和边界判断。
     */
    private static final Set<String> SECRET_KEYS =
            Set.of(
                    "apikey",
                    "token",
                    "accesstoken",
                    "refreshtoken",
                    "password",
                    "secret",
                    "clientsecret",
                    "authorization",
                    "cookie",
                    "setcookie");

    /**
     * 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    private final ObjectMapper mapper;

    /**
     * 创建Trace数据清理器，初始化该组件所需的状态、配置或依赖。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    public TraceDataSanitizer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 计算或取得本方法声明的结果，供当前TraceDataSanitizer处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的JSON节点结果。
     */
    public JsonNode sanitize(Object value) {
        JsonNode copy = mapper.valueToTree(value);
        redact(copy);
        return copy;
    }

    /**
     * 递归清理已知敏感字段名，不把该规则当作任意自由文本的敏感信息识别器。
     *
     * @param node 当前Trace数据清理器持有的节点对象，供相应处理步骤使用。
     */
    private static void redact(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.properties()
                    .forEach(
                            entry -> {
                                String key =
                                        entry.getKey()
                                                .toLowerCase(Locale.ROOT)
                                                .replaceAll("[-_]", "");
                                if (SECRET_KEYS.contains(key)) {
                                    object.put(entry.getKey(), "[REDACTED]");
                                } else {
                                    redact(entry.getValue());
                                }
                            });
        } else if (node.isArray()) {
            node.forEach(TraceDataSanitizer::redact);
        }
    }
}
