package dev.horizen.agent.tools.browser;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.tools.vision.VisionAnalyzeTool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 截取当前沙箱页面，并将生成的 Artifact 送入视觉分析。 */
public final class SandboxBrowserVisionTool extends ToolBase {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 当前浏览器截图产物或截图工具，供后续视觉解析使用。 */
    private final SandboxBrowserTool screenshot;

    /** 读取图片并生成视觉分析的执行依赖。 */
    private final VisionAnalyzeTool vision;

    /**
     * 创建沙箱浏览器视觉工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param screenshot 当前沙箱浏览器视觉工具持有的screenshot对象，供相应处理步骤使用。
     * @param vision 当前沙箱浏览器视觉工具持有的视觉对象，供相应处理步骤使用。
     */
    public SandboxBrowserVisionTool(SandboxBrowserTool screenshot, VisionAnalyzeTool vision) {
        super(
                ToolBase.builder()
                        .name("browser_vision")
                        .description("截取当前浏览器页面，并回答关于页面视觉内容的问题。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("question", Map.of("type", "string")),
                                        "required",
                                        List.of("question"),
                                        "additionalProperties",
                                        false))
                        .readOnly(true)
                        .concurrencySafe(false));
        this.screenshot = screenshot;
        this.vision = vision;
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前沙箱浏览器视觉工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolCallParam capture = ToolCallParam.builder(param).input(Map.of()).build();
        return screenshot
                .callAsync(capture)
                .flatMap(
                        result -> {
                            try {
                                String text = ((TextBlock) result.getOutput().get(0)).getText();
                                String artifactId =
                                        JSON.readTree(text).path("artifact_id").asText();
                                if (artifactId.isBlank())
                                    return Mono.just(
                                            ToolResultBlock.error(
                                                    "browser screenshot failed: " + text));
                                return vision.callAsync(
                                        ToolCallParam.builder(param)
                                                .input(
                                                        Map.of(
                                                                "artifact_id",
                                                                artifactId,
                                                                "question",
                                                                param.getInput().get("question")))
                                                .build());
                            } catch (Exception error) {
                                return Mono.just(
                                        ToolResultBlock.error(
                                                "browser vision failed: " + error.getMessage()));
                            }
                        });
    }
}
