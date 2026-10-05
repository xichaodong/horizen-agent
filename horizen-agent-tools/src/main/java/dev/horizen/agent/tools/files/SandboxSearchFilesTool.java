package dev.horizen.agent.tools.files;

import dev.horizen.agent.common.process.ShellQuoteUtils;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 沙箱内容和文件搜索工具，底层使用沙箱内的 rg/find。 */
public final class SandboxSearchFilesTool extends ToolBase {
    /** 创建沙箱检索文件集合工具，初始化该组件所需的状态、配置或依赖。 */
    public SandboxSearchFilesTool() {
        super(
                ToolBase.builder()
                        .name("search_files")
                        .description("按正则搜索文件内容，或按 glob 查找文件名；支持分页和 content/files_only/count 输出。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "pattern", Map.of("type", "string"),
                                                "target",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "enum",
                                                                List.of("content", "files"),
                                                                "default",
                                                                "content"),
                                                "path", Map.of("type", "string", "default", "."),
                                                "file_glob", Map.of("type", "string"),
                                                "limit",
                                                        Map.of(
                                                                "type", "integer", "minimum", 1,
                                                                "maximum", 500, "default", 50),
                                                "offset",
                                                        Map.of(
                                                                "type", "integer", "minimum", 0,
                                                                "default", 0),
                                                "output_mode",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "enum",
                                                                List.of(
                                                                        "content",
                                                                        "files_only",
                                                                        "count"),
                                                                "default",
                                                                "content"),
                                                "context",
                                                        Map.of(
                                                                "type", "integer", "minimum", 0,
                                                                "maximum", 20, "default", 0)),
                                        "required",
                                        List.of("pattern"),
                                        "additionalProperties",
                                        false))
                        .readOnly(true)
                        .concurrencySafe(true));
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前沙箱检索文件集合工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> search(param));
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxSearchFilesTool处理步骤使用。
     *
     * @param param 当前沙箱检索文件集合工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock search(ToolCallParam param) {
        AbstractFilesystem fs = param.getRuntimeContext().get(AbstractFilesystem.class);
        if (!(fs instanceof AbstractSandboxFilesystem sandbox)) {
            return ToolResultBlock.error("search_files requires an active sandbox");
        }
        String command;
        try {
            command = command(param);
        } catch (IllegalArgumentException error) {
            return ToolResultBlock.error(error.getMessage());
        }
        var result = sandbox.execute(param.getRuntimeContext(), command, 60);
        if (!result.isSuccess())
            return ToolResultBlock.error("search_files failed: " + result.output());
        String output = result.output() == null ? "" : result.output().strip();
        return ToolResultBlock.text(output.isBlank() ? "No matches." : output);
    }

    /**
     * 生成当前操作所需的command文本，供调用方继续处理。
     *
     * @param param 当前沙箱检索文件集合工具持有的参数对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    static String command(ToolCallParam param) {
        String pattern = text(param, "pattern", "");
        String target = text(param, "target", "content");
        String path = safePath(text(param, "path", "."));
        int limit = integer(param, "limit", 50, 1, 500);
        int offset = integer(param, "offset", 0, 0, 100_000);
        String command;
        if ("files".equals(target)) {
            command =
                    "find "
                            + quote(path)
                            + " -type f -name "
                            + quote(pattern)
                            + " -printf '%T@\\t%p\\n' | sort -rn | cut -f2- | sed -n '"
                            + (offset + 1)
                            + ","
                            + (offset + limit)
                            + "p'";
        } else {
            String mode = text(param, "output_mode", "content");
            int context = integer(param, "context", 0, 0, 20);
            String option = "files_only".equals(mode) ? "-l" : "count".equals(mode) ? "-c" : "-nH";
            String glob = text(param, "file_glob", "");
            command =
                    "rg --color never "
                            + option
                            + (context > 0 && "content".equals(mode) ? " -C " + context : "")
                            + (glob.isBlank() ? "" : " -g " + quote(glob))
                            + " -- "
                            + quote(pattern)
                            + " "
                            + quote(path)
                            + " | sed -n '"
                            + (offset + 1)
                            + ","
                            + (offset + limit)
                            + "p'";
        }
        command =
                "set -o pipefail; "
                        + command
                        + "; code=$?; "
                        + ("files".equals(target)
                                ? "exit \"$code\""
                                : "if [ \"$code\" -eq 1 ]; then exit 0; else exit \"$code\"; fi");
        return command;
    }

    /**
     * 生成当前操作所需的safePath文本，供调用方继续处理。
     *
     * @param raw 当前沙箱检索文件集合工具使用的原始，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static String safePath(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("\\"))
            throw new IllegalArgumentException("invalid search path");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().contains(".git")) {
            throw new IllegalArgumentException("search path must stay inside workspace");
        }
        return path.toString().isBlank() ? "." : path.toString();
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param param 当前沙箱检索文件集合工具持有的参数对象，供相应处理步骤使用。
     * @param key 当前对象的查找或写入键。
     * @param fallback 当前沙箱检索文件集合工具使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(ToolCallParam param, String key, String fallback) {
        Object value = param.getInput().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxSearchFilesTool处理步骤使用。
     *
     * @param param 当前沙箱检索文件集合工具持有的参数对象，供相应处理步骤使用。
     * @param key 当前对象的查找或写入键。
     * @param fallback 当前沙箱检索文件集合工具使用的回退，供其处理与状态记录使用。
     * @param min 当前沙箱检索文件集合工具使用的最小，供其处理与状态记录使用。
     * @param max 当前沙箱检索文件集合工具使用的最大，供其处理与状态记录使用。
     * @return 本次操作返回的整数结果。
     */
    private static int integer(ToolCallParam param, String key, int fallback, int min, int max) {
        Object value = param.getInput().get(key);
        int resolved = value instanceof Number number ? number.intValue() : fallback;
        return Math.max(min, Math.min(max, resolved));
    }

    /**
     * 转义沙箱检索文件集合工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String quote(String value) {
        return ShellQuoteUtils.quote(value);
    }
}
