package dev.horizen.agent.storage.jdbc.model;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 数据库行模型，与领域值对象分离。
 */
@Data
public class TurnRow {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private String sessionId;

    /**
     * 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     */
    private String requestId;

    /**
     * 实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     */
    private String actorId;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private String status;

    /**
     * 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     */
    private String executorId;

    /**
     * 当前执行或执行段的开始时间。
     */
    private Timestamp startedAt;

    /**
     * 执行结束时间；尚未结束的记录可以没有该时间。
     */
    private Timestamp finishedAt;

    /**
     * 当前操作允许继续执行的截止时间。
     */
    private Timestamp deadlineAt;

    /**
     * 执行实例租约到期时间，用于判断执行权是否仍有效。
     */
    private Timestamp leaseExpiresAt;

    /**
     * 机器可识别的失败分类，供状态恢复与错误展示使用。
     */
    private String failureCode;

    /**
     * 当前记录的创建时间。
     */
    private Timestamp createdAt;

    /**
     * 当前记录最近一次更新的时间。
     */
    private Timestamp updatedAt;

    /**
     * 记录版本，用于乐观并发控制或区分协议版本。
     */
    private Long version;
}
