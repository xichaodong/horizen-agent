package dev.horizen.agent.tool.adapter;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 宿主为单次 Agent Turn 注入的可信身份和执行坐标。 */
@Data
@NoArgsConstructor
public class ToolInvocationScope {
    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private String ownerKey;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private String turnId;

    /**
     * 创建工具调用作用域，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    public ToolInvocationScope(String ownerKey, String sessionId, String turnId) {
        this.ownerKey = required(ownerKey, "ownerKey");
        this.sessionId = required(sessionId, "sessionId");
        this.turnId = required(turnId, "turnId");
    }

    /**
     * 生成当前操作所需的required文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前工具调用作用域使用的字段，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String required(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
