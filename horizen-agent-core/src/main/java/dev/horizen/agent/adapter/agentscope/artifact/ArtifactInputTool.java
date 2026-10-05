package dev.horizen.agent.adapter.agentscope.artifact;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 让 Agent 在当前沙箱执行段中读取一个已授权 Artifact。 */
public final class ArtifactInputTool extends ToolBase {
    /** 解析与准备输入产物内容的服务。 */
    private final ArtifactInputService inputs;

    /**
     * 创建产物输入工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param inputs 提供输入集合能力的依赖，具体实现由当前组件的组装方传入。
     */
    public ArtifactInputTool(ArtifactInputService inputs) {
        super(
                ToolBase.builder()
                        .name("load_artifact")
                        .description("将已授权的 Artifact 下载到当前工作区。传 artifactId；可选 path 指定工作区相对路径。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "artifactId", Map.of("type", "string"),
                                                "path", Map.of("type", "string")),
                                        "required",
                                        List.of("artifactId"),
                                        "additionalProperties",
                                        false))
                        .readOnly(false)
                        .concurrencySafe(true));
        this.inputs = inputs;
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前产物输入工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            Map<String, Object> input = param.getInput();
            String artifactId =
                    input == null || input.get("artifactId") == null
                            ? ""
                            : String.valueOf(input.get("artifactId")).trim();
            String path =
                    input == null || input.get("path") == null
                            ? null
                            : String.valueOf(input.get("path"));
            if (artifactId.isEmpty()) {
                return Mono.just(ToolResultBlock.error("artifactId must not be blank"));
            }
            String uploaded = inputs.materialize(param.getRuntimeContext(), artifactId, path);
            return Mono.just(
                    ToolResultBlock.text(
                            "Artifact " + artifactId + " is available at " + uploaded));
        } catch (RuntimeException error) {
            return Mono.just(
                    ToolResultBlock.error(
                            "artifact load failed: " + error.getClass().getSimpleName()));
        }
    }
}
