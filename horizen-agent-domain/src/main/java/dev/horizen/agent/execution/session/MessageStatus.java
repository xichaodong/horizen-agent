package dev.horizen.agent.execution.session;

/**
 * DRAFT 仅用于执行中展示，FINAL 才属于正式对话。
 */
public enum MessageStatus {
    /**
     * 尚未完成提交的消息草稿。
     */
    DRAFT,
    /**
     * 已经提交的正式消息内容。
     */
    FINAL
}
