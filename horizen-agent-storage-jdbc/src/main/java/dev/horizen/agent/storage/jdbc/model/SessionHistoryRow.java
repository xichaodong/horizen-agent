package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 统一会话历史表的一条持久化记录，用于还原消息、事件或呈现块。
 */
@Data
public class SessionHistoryRow {
    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private String sessionId;

    /**
     * 当前会话历史存储记录的可读标题，供宿主界面展示。
     */
    private String title;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 历史或审计记录的标识，用于定位单条持久化事实。
     */
    private String recordId;

    /**
     * 正式消息的发送角色，用于恢复用户与助手对话。
     */
    private String messageRole;

    /**
     * 会话正式消息的顺序号，与执行事件游标分开使用。
     */
    private Long messageSequence;

    /**
     * 当前记录的创建时间。
     */
    private Timestamp createdAt;

    /**
     * 当前检索命中的正文片段，实际起点和总长度由相应字段说明。
     */
    private String excerpt;

    /**
     * 本次返回文本片段在原正文中的字符偏移。
     */
    private Integer contentOffset;

    /**
     * 原正文的字符长度，用于判断片段之后是否仍有内容。
     */
    private Long contentLength;
}
