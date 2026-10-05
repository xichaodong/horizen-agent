package dev.horizen.agent.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 为独立命令和真实环境验收读取 YAML，将层级配置展开为点分隔的键，不解析对象类型标签。 */
public final class YamlConfigFiles {
    /** 只读取配置树；保留调用方传入流的所有权，并拒绝同一映射中的重复键。 */
    private static final ObjectMapper YAML =
            new ObjectMapper(new YAMLFactory())
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);

    /** 静态配置读取入口不持有实例状态。 */
    private YamlConfigFiles() {}

    /** 读取 UTF-8 YAML 文件并关闭本方法创建的输入流；空文件返回空配置。 */
    public static Properties load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return load(input);
        }
    }

    /** 读取 YAML 字节流；流由调用方负责关闭，配置值和凭据不写入日志。 */
    public static Properties load(InputStream input) throws IOException {
        return flatten(YAML.readTree(input));
    }

    /** 读取调用方已选择字符编码的 YAML；Reader 由调用方负责关闭。 */
    public static Properties load(Reader reader) throws IOException {
        return flatten(YAML.readTree(reader));
    }

    /** 只接受映射作为配置根节点；拒绝被当成 YAML 标量读取的旧键值文件。 */
    private static Properties flatten(JsonNode root) {
        Properties result = new Properties();
        if (root == null || root.isNull()) return result;
        if (!root.isObject())
            throw new IllegalArgumentException("YAML configuration requires a mapping root");
        collect(result, "", root);
        return result;
    }

    /** 展开映射和列表；列表采用 Spring 的下标键形式，null 作为空文本保留。 */
    private static void collect(Properties result, String prefix, JsonNode value) {
        if (value.isObject()) {
            value.fields()
                    .forEachRemaining(
                            entry ->
                                    collect(
                                            result,
                                            prefix.isEmpty()
                                                    ? entry.getKey()
                                                    : prefix + "." + entry.getKey(),
                                            entry.getValue()));
        } else if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                collect(result, prefix + "[" + index + "]", value.get(index));
            }
        } else {
            result.setProperty(prefix, value.isNull() ? "" : value.asText());
        }
    }
}
