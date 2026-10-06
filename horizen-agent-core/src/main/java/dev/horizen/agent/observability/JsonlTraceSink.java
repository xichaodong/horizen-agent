package dev.horizen.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 每行追加一个 JSON 对象，并在每个事件后刷新，便于本地调试。
 */
public final class JsonlTraceSink implements TraceSink, AutoCloseable {
    /**
     * 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    private final ObjectMapper mapper = JsonUtils.newMapper();

    /**
     * 按已知敏感字段名清理观测负载的脱敏器，不识别任意自由文本中的秘密。
     */
    private final TraceDataSanitizer sanitizer = new TraceDataSanitizer(mapper);

    /**
     * 将输出写入当前内容目标的写入器。
     */
    private final BufferedWriter writer;

    /**
     * 创建JSONLTrace上报端，初始化该组件所需的状态、配置或依赖。
     *
     * @param path 需要读取、写入或校验的路径。
     */
    public JsonlTraceSink(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        writer =
                Files.newBufferedWriter(
                        absolute,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
    }

    /**
     * 将事件以单行 JSON 追加到本地 Trace 文件，应用字段名脱敏并刷新开发观察输出。
     *
     * @param event 当前JSONLTrace上报端持有的事件对象，供相应处理步骤使用。
     * @throws UncheckedIOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public synchronized void record(TraceEvent event) {
        var node = sanitizer.sanitize(event);
        try {
            writer.write(mapper.writeValueAsString(node));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write trace event", e);
        }
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     */
    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
