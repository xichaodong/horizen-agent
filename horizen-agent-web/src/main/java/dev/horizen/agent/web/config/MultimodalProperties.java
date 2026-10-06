package dev.horizen.agent.web.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主模型请求中直接图像块的容量限制和能力开关。
 */
@ConfigurationProperties(prefix = "horizen.agent.multimodal")
@Getter
@EqualsAndHashCode
@ToString
public class MultimodalProperties {
    /**
     * 是否启用direct图片输入处理。
     */
    private final boolean directImageInputEnabled;

    /**
     * 单次执行允许携带的直接图片输入数量上限。
     */
    private final int maxImagesPerTurn;

    /**
     * 最大图片的字节数，用于容量或传输限制。
     */
    private final long maxImageBytes;

    /**
     * 最大总计图片的字节数，用于容量或传输限制。
     */
    private final long maxTotalImageBytes;

    /**
     * 图片URL过期，单位为秒。
     */
    private final int imageUrlExpiresSeconds;

    /**
     * 创建多模态配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param directImageInputEnabled 是否启用direct图片输入处理。
     * @param maxImagesPerTurn        当前多模态配置使用的最大图片集合每项执行，供其处理与状态记录使用。
     * @param maxImageBytes           最大图片的字节数，用于容量或传输限制。
     * @param maxTotalImageBytes      最大总计图片的字节数，用于容量或传输限制。
     * @param imageUrlExpiresSeconds  图片URL过期，单位为秒。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public MultimodalProperties(
            Boolean directImageInputEnabled,
            Integer maxImagesPerTurn,
            Long maxImageBytes,
            Long maxTotalImageBytes,
            Integer imageUrlExpiresSeconds) {
        this.directImageInputEnabled = directImageInputEnabled == null || directImageInputEnabled;
        this.maxImagesPerTurn = maxImagesPerTurn == null ? 5 : maxImagesPerTurn;
        this.maxImageBytes = maxImageBytes == null ? 10L * 1024 * 1024 : maxImageBytes;
        this.maxTotalImageBytes =
                maxTotalImageBytes == null ? 20L * 1024 * 1024 : maxTotalImageBytes;
        this.imageUrlExpiresSeconds =
                imageUrlExpiresSeconds == null ? 3600 : imageUrlExpiresSeconds;
        if (this.maxImagesPerTurn < 1 || this.maxImagesPerTurn > 20) {
            throw new IllegalArgumentException("maxImagesPerTurn must be between 1 and 20");
        }
        if (this.maxImageBytes < 1 || this.maxTotalImageBytes < this.maxImageBytes) {
            throw new IllegalArgumentException("multimodal byte limits are invalid");
        }
        if (this.imageUrlExpiresSeconds < 60 || this.imageUrlExpiresSeconds > 86400) {
            throw new IllegalArgumentException(
                    "imageUrlExpiresSeconds must be between 60 and 86400");
        }
    }
}
