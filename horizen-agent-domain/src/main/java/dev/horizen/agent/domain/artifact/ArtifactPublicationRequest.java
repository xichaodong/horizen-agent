package dev.horizen.agent.domain.artifact;

import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/** 从执行结果发布一个文件 Artifact 的请求。 */
public final class ArtifactPublicationRequest {
    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    @Getter private final String ownerKey;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    @Getter private final String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    @Getter private final String turnId;

    /** 来源资源的引用，供追踪产物或事件的生成来源。 */
    @Getter private final String sourceRef;

    /** 当前产物发布请求的可读标题，供宿主界面展示。 */
    @Getter private final String title;

    /** 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。 */
    @Getter private final String mediaType;

    /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
    private final byte[] content;

    /** 来源产物的标识，用于串联修改前后的版本关系。 */
    @Getter private final String parentArtifactId;

    /** 当前记录或授权的失效时间，用于过期检查。 */
    @Getter private final Instant expiresAt;

    /** 本次领域操作采用的当前时间，统一用于状态和时间字段更新。 */
    @Getter private final Instant now;

    /**
     * 创建产物发布请求，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param sourceRef 来源资源的引用，供追踪产物或事件的生成来源。
     * @param title 当前产物发布请求的可读标题，供宿主界面展示。
     * @param mediaType 当前产物发布请求使用的媒体类型，供其处理与状态记录使用。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param parentArtifactId 来源产物的标识，用于串联修改前后的版本关系。
     * @param expiresAt 当前记录或授权的失效时间，用于过期检查。
     * @param now 用于本次更新或过期判断的当前时间。
     */
    public ArtifactPublicationRequest(
            String ownerKey,
            String sessionId,
            String turnId,
            String sourceRef,
            String title,
            String mediaType,
            byte[] content,
            String parentArtifactId,
            Instant expiresAt,
            Instant now) {
        this.ownerKey = require(ownerKey, "ownerKey");
        this.sessionId = require(sessionId, "sessionId");
        this.turnId = require(turnId, "turnId");
        this.sourceRef = require(sourceRef, "sourceRef");
        this.title = require(title, "title");
        this.mediaType = require(mediaType, "mediaType");
        this.content = Objects.requireNonNull(content, "content").clone();
        this.parentArtifactId =
                parentArtifactId == null || parentArtifactId.isBlank()
                        ? null
                        : parentArtifactId.trim();
        this.expiresAt = expiresAt;
        this.now = Objects.requireNonNull(now, "now");
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
     * 取得并校验产物发布请求。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前产物发布请求使用的字段，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
