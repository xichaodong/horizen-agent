package dev.horizen.agent.domain.presentation;

import dev.horizen.agent.domain.artifact.ArtifactDescriptor;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 工具生成、由宿主渲染的声明式展示块，不携带可执行的界面代码。
 */
@Getter
public final class PresentationBlock {
    /**
     * 结构化呈现块的标识，客户端用它去重与更新同一张卡片。
     */
    private final String blockId;

    /**
     * 本对象的协议类别，用于选择对应的解析或呈现规则。
     */
    private final String type;

    /**
     * Schema的版本，供兼容或并发检查使用。
     */
    private final int schemaVersion;

    /**
     * 当前块在其列表或时间线中的位置，用于稳定排序。
     */
    private final int position;

    /**
     * 数据的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<String, Object> data;

    /**
     * 创建呈现块，初始化该组件所需的状态、配置或依赖。
     *
     * @param blockId       结构化呈现块的标识，客户端用它去重与更新同一张卡片。
     * @param type          当前操作使用的目标类型或类别。
     * @param schemaVersion Schema的版本，供兼容或并发检查使用。
     * @param position      当前呈现块使用的位置，供其处理与状态记录使用。
     * @param data          当前操作处理的数据。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public PresentationBlock(
            String blockId,
            String type,
            int schemaVersion,
            int position,
            Map<String, Object> data) {
        this.blockId = require(blockId, "blockId");
        this.type = require(type, "type");
        if (!type.matches("[a-z][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("presentation type is invalid");
        }
        if (schemaVersion < 1 || schemaVersion > 1000) {
            throw new IllegalArgumentException("presentation schemaVersion is invalid");
        }
        if (position < 0 || position > 999) {
            throw new IllegalArgumentException("presentation position is invalid");
        }
        this.schemaVersion = schemaVersion;
        this.position = position;
        this.data = Map.copyOf(data == null ? Map.of() : new LinkedHashMap<>(data));
    }

    /**
     * 为持久化 Artifact 创建统一的通用展示形式。
     */
    public static PresentationBlock artifactCard(ArtifactDescriptor artifact, int position) {
        if (artifact == null) throw new IllegalArgumentException("artifact is required");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("artifactId", artifact.getArtifactId());
        data.put("variant", artifactVariant(artifact.getMediaType()));
        data.put("title", artifact.getTitle());
        data.put("kind", artifact.getKind().name().toLowerCase(Locale.ROOT));
        if (artifact.getMediaType() != null) data.put("mediaType", artifact.getMediaType());
        if (artifact.getSizeBytes() != null) data.put("sizeBytes", artifact.getSizeBytes());
        if (artifact.getParentArtifactId() != null) {
            data.put("parentArtifactId", artifact.getParentArtifactId());
        }
        String uuid =
                UUID.nameUUIDFromBytes(
                                ("artifact\u0000" + artifact.getArtifactId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
        return new PresentationBlock("ui_art_" + uuid, "artifact_card", 1, position, data);
    }

    /**
     * 生成当前操作所需的artifactVariant文本，供调用方继续处理。
     *
     * @param mediaType 当前呈现块使用的媒体类型，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String artifactVariant(String mediaType) {
        if (mediaType == null) return "file";
        String normalized = mediaType.toLowerCase(Locale.ROOT);
        if ("text/markdown".equals(normalized)) return "report";
        if (normalized.startsWith("image/")) return "image";
        return "file";
    }

    /**
     * 取得并校验呈现块。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name  需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String require(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
