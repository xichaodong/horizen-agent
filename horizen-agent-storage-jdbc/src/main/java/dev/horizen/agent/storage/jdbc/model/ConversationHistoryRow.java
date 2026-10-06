package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 数据库行模型，与领域值对象分离。
 */
@Data
public class ConversationHistoryRow {
    /**
     * 持久会话时间线的顺序号，用于分页和历史排列。
     */
    private Long historySequence;

    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private String sessionId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 历史记录类别，用于区分消息、过程事实与呈现块。
     */
    private String recordType;

    /**
     * 历史或审计记录的标识，用于定位单条持久化事实。
     */
    private String recordId;

    /**
     * 会话正式消息的顺序号，与执行事件游标分开使用。
     */
    private Long messageSequence;

    /**
     * 产物资源标识；访问内容时仍需校验所属隔离范围。
     */
    private String artifactId;

    /**
     * 产物在当前消息或执行中的输入、输出或工作引用角色。
     */
    private String artifactRole;

    /**
     * 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     */
    private String payloadJson;

    /**
     * 正式会话时间线的排列序号，与临时事件回放游标区分。
     */
    private Long timelineSequence;

    /**
     * 时间线负载的 JSON 表示，供持久化或协议转换使用。
     */
    private String timelinePayloadJson;

    /**
     * 时间线创建的时间，用于记录对应生命周期节点。
     */
    private Timestamp timelineCreatedAt;

    /**
     * 当前记录的创建时间。
     */
    private Timestamp createdAt;

    /**
     * 当前记录最近一次更新的时间。
     */
    private Timestamp updatedAt;

    /**
     * 过程事件在所属记录或执行范围中的顺序号。
     */
    private Long eventSequence;
}
