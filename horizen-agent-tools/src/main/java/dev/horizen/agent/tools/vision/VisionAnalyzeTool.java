package dev.horizen.agent.tools.vision;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactImagePolicy;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 将所有者的图像 Artifact 发送给独立视觉模型，只向主模型返回文字结果。
 */
public final class VisionAnalyzeTool extends ToolBase {
    /**
     * 最大图片字节的固定取值，用于相应策略和边界判断。
     */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /**
     * 独立视觉模型，接收图片输入并生成文字；宿主不能把主模型实例传入此字段。
     */
    private final Model model;

    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    private final ArtifactStore artifacts;

    /**
     * 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    private final ArtifactContentStore contents;

    /**
     * 图片URL过期，单位为秒。
     */
    private final int imageUrlExpiresSeconds;

    /**
     * 创建视觉分析工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param model     当前视觉分析工具持有的模型对象，供相应处理步骤使用。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param contents  资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    public VisionAnalyzeTool(Model model, ArtifactStore artifacts, ArtifactContentStore contents) {
        this(model, artifacts, contents, 3600);
    }

    /**
     * 创建视觉分析工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param model                  当前视觉分析工具持有的模型对象，供相应处理步骤使用。
     * @param artifacts              产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param contents               资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @param imageUrlExpiresSeconds 图片URL过期，单位为秒。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public VisionAnalyzeTool(
            Model model,
            ArtifactStore artifacts,
            ArtifactContentStore contents,
            int imageUrlExpiresSeconds) {
        super(
                ToolBase.builder()
                        .name("vision_analyze")
                        .description("分析已上传或已生成的图片 Artifact，并回答具体问题。仅支持当前用户拥有的图片。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "artifact_id",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "图片 Artifact ID"),
                                                "question",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "希望分析图片的具体问题")),
                                        "required",
                                        List.of("artifact_id", "question"),
                                        "additionalProperties",
                                        false))
                        .readOnly(true)
                        .concurrencySafe(true));
        this.model = model;
        this.artifacts = artifacts;
        this.contents = contents;
        if (imageUrlExpiresSeconds <= 0) {
            throw new IllegalArgumentException("imageUrlExpiresSeconds must be positive");
        }
        this.imageUrlExpiresSeconds = imageUrlExpiresSeconds;
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前视觉分析工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        if (model == null
                || artifacts == null
                || contents == null
                || param.getRuntimeContext() == null) {
            return Mono.just(ToolResultBlock.error("Independent vision model is not configured; enable horizen.agent.vision and set its model-name"));
        }
        String id = String.valueOf(param.getInput().getOrDefault("artifact_id", "")).trim();
        String question = String.valueOf(param.getInput().getOrDefault("question", "")).trim();
        if (id.isEmpty() || question.isEmpty()) {
            return Mono.just(ToolResultBlock.error("artifact_id and question are required"));
        }
        Artifact artifact;
        try {
            artifact =
                    artifacts
                            .find(param.getRuntimeContext().getUserId(), id)
                            .orElseThrow(
                                    () -> new IllegalArgumentException("image artifact not found"));
            if (artifact.getState() != ArtifactState.READY || artifact.getContentRef() == null) {
                throw new IllegalArgumentException("image artifact is not ready");
            }
            ArtifactImagePolicy.requireSupportedMediaType(artifact.getMediaType());
            if (artifact.getSizeBytes() != null && artifact.getSizeBytes() > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("image exceeds 10 MiB limit");
            }
        } catch (IllegalArgumentException error) {
            return Mono.just(ToolResultBlock.error(error.getMessage()));
        }
        String imageUrl;
        try {
            imageUrl =
                    contents.createDownloadUrl(artifact.getContentRef(), imageUrlExpiresSeconds)
                            .toString();
        } catch (RuntimeException error) {
            return Mono.just(
                    ToolResultBlock.error(
                            "image URL creation failed: " + error.getClass().getSimpleName()));
        }
        Msg request =
                new UserMessage(
                        List.of(
                                TextBlock.builder().text(question).build(),
                                ImageBlock.builder()
                                        .source(new URLSource(imageUrl, artifact.getMediaType()))
                                        .build()));
        return model.stream(List.of(request), List.of(), null)
                .collectList()
                .map(
                        responses -> {
                            String answer =
                                    responses.stream()
                                            .flatMap(value -> value.getContent().stream())
                                            .filter(TextBlock.class::isInstance)
                                            .map(TextBlock.class::cast)
                                            .map(TextBlock::getText)
                                            .reduce("", String::concat);
                            return answer.isBlank()
                                    ? ToolResultBlock.error("vision model returned no text")
                                    : ToolResultBlock.of(TextBlock.builder().text(answer).build(), Map.of("visionModel", model.getModelName()));
                        })
                .onErrorResume(
                        error ->
                                Mono.just(
                                        ToolResultBlock.error(
                                                "vision analysis failed: "
                                                        + error.getClass().getSimpleName())));
    }
}
