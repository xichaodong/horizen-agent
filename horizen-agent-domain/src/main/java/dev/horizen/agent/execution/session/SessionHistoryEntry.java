package dev.horizen.agent.execution.session;

import lombok.Value;

import java.time.Instant;

/** 长度受限的摘要，不包含完整工具调用记录或其他所有者的身份信息。 */
@Value
public class SessionHistoryEntry {
    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    String sessionId;

    /** 当前会话历史条目的可读标题，供宿主界面展示。 */
    String title;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    String turnId;

    /** 会话消息的标识，用于历史查询与过程事件关联。 */
    String messageId;

    /** 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。 */
    MessageRole role;

    /** 当前记录在对应序列中的位置，用于排序或继续读取。 */
    long sequence;

    /** 当前记录的创建时间。 */
    Instant createdAt;

    /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
    String content;

    /** 本次返回文本片段在原正文中的字符偏移。 */
    int contentOffset;

    /** 原正文的字符长度，用于判断片段之后是否仍有内容。 */
    long contentLength;
}
