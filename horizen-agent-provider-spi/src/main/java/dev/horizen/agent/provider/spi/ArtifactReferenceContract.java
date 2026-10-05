package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 外部工具结果中的产物引用契约，不承载实际文件内容。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactReferenceContract {
    /** 产物资源标识；访问内容时仍需校验所属隔离范围。 */
    private String artifactId;

    /** 当前产物引用契约的可读标题，供宿主界面展示。 */
    private String title;

    /** 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。 */
    private String mediaType;

    /** 内容大小，单位为字节。 */
    private Long sizeBytes;

    /**
     * 校验当前产物引用契约的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的产物引用契约结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ArtifactReferenceContract validate() {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) {
            throw new IllegalArgumentException("artifactId is invalid");
        }
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("artifact title is required");
        if (sizeBytes != null && sizeBytes < 0)
            throw new IllegalArgumentException("sizeBytes is invalid");
        return this;
    }
}
