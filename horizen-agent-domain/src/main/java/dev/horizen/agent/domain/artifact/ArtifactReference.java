package dev.horizen.agent.domain.artifact;

import dev.horizen.agent.common.validation.Identifiers;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一次 Session/Turn 对 Artifact 的可信引用。
 */
@Getter
@EqualsAndHashCode
@ToString
public class ArtifactReference {

    /**
     * 引用的标识，用于关联相应记录或执行。
     */
    private final String referenceId;

    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private final String ownerKey;

    /**
     * 产物资源标识；访问内容时仍需校验所属隔离范围。
     */
    private final String artifactId;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private final String sessionId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private final String turnId;

    /**
     * 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
     */
    private final ArtifactReferenceRole role;

    /**
     * 一次工具调用的标识，用于配对参数、结果和审批事件。
     */
    private final String toolCallId;

    /**
     * 当前记录的创建时间。
     */
    private final Instant createdAt;

    /**
     * 创建产物引用，初始化该组件所需的状态、配置或依赖。
     *
     * @param referenceId 引用的标识，用于关联相应记录或执行。
     * @param ownerKey    宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId  产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param sessionId   会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId      单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param role        消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
     * @param toolCallId  一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param createdAt   当前记录的创建时间。
     */
    @ConstructorProperties({
            "referenceId",
            "ownerKey",
            "artifactId",
            "sessionId",
            "turnId",
            "role",
            "toolCallId",
            "createdAt"
    })
    public ArtifactReference(
            String referenceId,
            String ownerKey,
            String artifactId,
            String sessionId,
            String turnId,
            ArtifactReferenceRole role,
            String toolCallId,
            Instant createdAt) {
        this.referenceId = require(referenceId, "referenceId", Identifiers.ARTIFACT_REFERENCE_ID);
        this.ownerKey = require(ownerKey, "ownerKey", Identifiers.ARTIFACT_OWNER_KEY);
        this.artifactId = require(artifactId, "artifactId", Identifiers.ARTIFACT_ID);
        this.sessionId = require(sessionId, "sessionId", Identifiers.STORED_SESSION_ID);
        this.turnId = require(turnId, "turnId", Identifiers.STORED_TURN_ID);
        this.role = Objects.requireNonNull(role, "role");
        this.toolCallId = optional(toolCallId, "toolCallId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 取得并校验产物引用。
     *
     * @param value   待校验、转换或保存的原始值。
     * @param field   当前产物引用使用的字段，供其处理与状态记录使用。
     * @param pattern 当前产物引用持有的校验模式对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String require(String value, String field, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be an opaque safe identifier");
        }
        return value;
    }

    /**
     * 生成当前操作所需的optional文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前产物引用使用的字段，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String optional(String value, String field) {
        return value == null || value.isBlank()
                ? null
                : require(value, field, Identifiers.OPAQUE_ID);
    }
}
