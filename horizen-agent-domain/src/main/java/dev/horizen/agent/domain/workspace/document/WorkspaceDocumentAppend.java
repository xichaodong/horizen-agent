package dev.horizen.agent.domain.workspace.document;

import lombok.Data;

import java.util.Objects;

/** 云端工作区提交中的单次幂等追加。 */
@Data
public final class WorkspaceDocumentAppend {
    /** 组合资源归属与作用域的定位键，供仓储查询和更新使用。 */
    private final WorkspaceDocumentKey key;

    /** 单次写入操作的标识，用于幂等提交和操作追踪。 */
    private final String operationId;

    /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
    private final String content;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private final String turnId;

    /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
    private final String toolCallId;

    /**
     * 创建工作区文档追加，初始化该组件所需的状态、配置或依赖。
     *
     * @param key 当前对象的查找或写入键。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     */
    public WorkspaceDocumentAppend(WorkspaceDocumentKey key, String operationId, String content) {
        this(key, operationId, content, null, null, null);
    }

    /**
     * 创建工作区文档追加，初始化该组件所需的状态、配置或依赖。
     *
     * @param key 当前对象的查找或写入键。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public WorkspaceDocumentAppend(
            WorkspaceDocumentKey key,
            String operationId,
            String content,
            String sessionId,
            String turnId,
            String toolCallId) {
        this.key = Objects.requireNonNull(key, "key");
        if (operationId == null || operationId.isBlank() || operationId.length() > 191) {
            throw new IllegalArgumentException("Invalid operationId");
        }
        this.operationId = operationId;
        this.content = Objects.requireNonNull(content, "content");
        this.sessionId = optional(sessionId);
        this.turnId = optional(turnId);
        this.toolCallId = optional(toolCallId);
    }

    /**
     * 生成当前操作所需的optional文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String optional(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 191 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid operation coordinate");
        return value;
    }
}
