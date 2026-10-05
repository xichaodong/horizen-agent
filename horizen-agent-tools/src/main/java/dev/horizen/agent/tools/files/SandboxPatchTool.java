package dev.horizen.agent.tools.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.json.JsonUtils;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 沙箱文件补丁工具，支持文本替换和 V4A 多文件补丁。 */
public final class SandboxPatchTool extends ToolBase {
    /** 最大补丁字节的固定取值，用于相应策略和边界判断。 */
    private static final int MAX_PATCH_BYTES = 1024 * 1024;

    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 创建沙箱补丁工具，初始化该组件所需的状态、配置或依赖。 */
    public SandboxPatchTool() {
        super(
                ToolBase.builder()
                        .name("patch")
                        .description("修改沙箱内文件。replace 模式做定向替换；patch 模式应用 V4A 多文件补丁。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "mode",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "enum",
                                                                List.of("replace", "patch"),
                                                                "default",
                                                                "replace"),
                                                "path", Map.of("type", "string"),
                                                "old_string", Map.of("type", "string"),
                                                "new_string", Map.of("type", "string"),
                                                "replace_all",
                                                        Map.of("type", "boolean", "default", false),
                                                "patch",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "V4A patch 内容")),
                                        "required",
                                        List.of("mode"),
                                        "additionalProperties",
                                        false))
                        .readOnly(false)
                        .concurrencySafe(false));
    }

    /**
     * 检查权限集合。
     *
     * @param input 本次处理的输入。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input, PermissionContextState context) {
        try {
            validateInput(input);
            return Mono.just(PermissionDecision.passthrough("validated workspace patch"));
        } catch (IllegalArgumentException error) {
            return Mono.just(PermissionDecision.deny(error.getMessage()));
        }
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前沙箱补丁工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> apply(param));
    }

    /**
     * 应用沙箱补丁工具。
     *
     * @param param 当前沙箱补丁工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock apply(ToolCallParam param) {
        try {
            validateInput(param.getInput());
        } catch (IllegalArgumentException error) {
            return ToolResultBlock.error(error.getMessage());
        }
        AbstractFilesystem fs = param.getRuntimeContext().get(AbstractFilesystem.class);
        if (fs == null) return ToolResultBlock.error("patch requires an active workspace");
        String mode = text(param.getInput(), "mode", "replace");
        try {
            if ("replace".equals(mode)) {
                String path = V4aPatchEngine.safe(text(param.getInput(), "path", ""));
                var result =
                        fs.edit(
                                param.getRuntimeContext(),
                                path,
                                text(param.getInput(), "old_string", null),
                                text(param.getInput(), "new_string", null),
                                Boolean.TRUE.equals(param.getInput().get("replace_all")));
                if (!result.isSuccess()) return ToolResultBlock.error(result.error());
                ObjectNode output =
                        JSON.createObjectNode()
                                .put("success", true)
                                .put("mode", "replace")
                                .put("path", path)
                                .put(
                                        "occurrences",
                                        result.occurrences() == null ? 0 : result.occurrences());
                return ToolResultBlock.text(output.toString());
            }
            V4aPatchEngine.Result result =
                    V4aPatchEngine.apply(
                            fs, param.getRuntimeContext(), text(param.getInput(), "patch", ""));
            ObjectNode output = JSON.createObjectNode().put("success", true).put("mode", "patch");
            output.set("files_modified", JSON.valueToTree(result.getModified()));
            return ToolResultBlock.text(output.toString());
        } catch (RuntimeException error) {
            return ToolResultBlock.error(error.getMessage());
        }
    }

    /**
     * 校验输入。
     *
     * @param input 本次处理的输入。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static void validateInput(Map<String, Object> input) {
        String mode = text(input, "mode", "replace");
        if ("replace".equals(mode)) {
            V4aPatchEngine.safe(text(input, "path", ""));
            if (!input.containsKey("old_string") || !input.containsKey("new_string")) {
                throw new IllegalArgumentException("old_string and new_string required");
            }
        } else if ("patch".equals(mode)) {
            String patch = text(input, "patch", "");
            if (patch.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES) {
                throw new IllegalArgumentException("patch exceeds 1 MiB limit");
            }
            V4aPatchEngine.parse(patch);
        } else throw new IllegalArgumentException("Unknown mode: " + mode);
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param input 本次处理的输入。
     * @param key 当前对象的查找或写入键。
     * @param fallback 当前沙箱补丁工具使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(Map<String, Object> input, String key, String fallback) {
        Object value = input.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
