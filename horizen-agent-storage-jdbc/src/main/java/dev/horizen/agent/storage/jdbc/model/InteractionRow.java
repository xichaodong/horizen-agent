package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/** 数据库行模型，与领域值对象分离。 */
@Data
public class InteractionRow {
    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private String ownerKey;

    /** 交互记录类别，用于区分审批与用户澄清。 */
    private String interactionType;

    /** 交互的标识，用于关联相应记录或执行。 */
    private String interactionId;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private String turnId;

    /** 回复的标识，用于关联相应记录或执行。 */
    private String replyId;

    /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
    private String toolCallId;

    /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
    private String status;

    /** 请求的 JSON 表示，供持久化或协议转换使用。 */
    private String requestJson;

    /** 响应的 JSON 表示，供持久化或协议转换使用。 */
    private String responseJson;

    /** 当前记录或授权的失效时间，用于过期检查。 */
    private Timestamp expiresAt;

    /** 已解析的时间，用于记录对应生命周期节点。 */
    private Timestamp resolvedAt;

    /** 当前记录的创建时间。 */
    private Timestamp createdAt;

    /** 当前记录最近一次更新的时间。 */
    private Timestamp updatedAt;

    /** 记录版本，用于乐观并发控制或区分协议版本。 */
    private Long version;
}
