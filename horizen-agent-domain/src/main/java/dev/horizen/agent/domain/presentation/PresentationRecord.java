package dev.horizen.agent.domain.presentation;

import lombok.Getter;

import java.time.Instant;

/** 单个展示块的持久化所有者、会话和 Turn 绑定。 */
@Getter
public final class PresentationRecord {
    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private final String ownerKey;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private final String turnId;

    /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
    private final String toolCallId;

    /** 可调用工具的注册名称，须与目录中声明的名称一致。 */
    private final String toolName;

    /** 当前需要保存或展示的结构化呈现块。 */
    private final PresentationBlock block;

    /** 当前记录的创建时间。 */
    private final Instant createdAt;

    /**
     * 创建呈现记录，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param toolName 可调用工具的注册名称，须与目录中声明的名称一致。
     * @param block 当前呈现记录持有的块对象，供相应处理步骤使用。
     * @param createdAt 当前记录的创建时间。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public PresentationRecord(
            String ownerKey,
            String sessionId,
            String turnId,
            String toolCallId,
            String toolName,
            PresentationBlock block,
            Instant createdAt) {
        this.ownerKey = require(ownerKey, "ownerKey");
        this.sessionId = require(sessionId, "sessionId");
        this.turnId = require(turnId, "turnId");
        this.toolCallId = require(toolCallId, "toolCallId");
        this.toolName = require(toolName, "toolName");
        if (block == null) throw new IllegalArgumentException("block is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        this.block = block;
        this.createdAt = createdAt;
    }

    /**
     * 取得并校验呈现记录。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name 需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String require(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
