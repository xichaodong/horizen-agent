package dev.horizen.agent.domain.artifact;

import dev.horizen.agent.common.validation.Identifiers;

import lombok.Getter;

import java.util.regex.Pattern;

/** 向内容存储 Provider 写入 Artifact 内容的请求。 */
public final class ArtifactContentWrite {

    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    @Getter private final String ownerKey;

    /** 产物资源标识；访问内容时仍需校验所属隔离范围。 */
    @Getter private final String artifactId;

    /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
    private final byte[] content;

    /** 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。 */
    @Getter private final String mediaType;

    /**
     * 创建产物正文写入，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param mediaType 当前产物正文写入使用的媒体类型，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ArtifactContentWrite(
            String ownerKey, String artifactId, byte[] content, String mediaType) {
        this.ownerKey = require(ownerKey, "ownerKey", Identifiers.ARTIFACT_OWNER_KEY);
        this.artifactId = require(artifactId, "artifactId", Identifiers.ARTIFACT_ID);
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        this.content = content.clone();
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        this.mediaType = mediaType.trim();
    }

    /**
     * 读取正文的当前值。
     * 处理数组时使用副本，避免直接共享原数组内容。
     *
     * @return {@link #content} 中保存的值。
     */
    public byte[] content() {
        return content.clone();
    }

    /**
     * 取得并校验产物正文写入。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前产物正文写入使用的字段，供其处理与状态记录使用。
     * @param pattern 当前产物正文写入持有的校验模式对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String require(String value, String field, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be an opaque safe identifier");
        }
        return value;
    }
}
