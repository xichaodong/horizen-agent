package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 可信执行坐标与模型生成的业务参数。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderInvokeRequest {
    /** 协议的版本，供兼容或并发检查使用。 */
    private int protocolVersion = ProviderProtocol.CURRENT_VERSION;

    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private String ownerKey;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private String turnId;

    /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
    private String toolCallId;

    /** 可调用工具的注册名称，须与目录中声明的名称一致。 */
    private String toolName;

    /** 当前操作的输入数据，格式由所属命令、协议或工具定义。 */
    private Map<String, Object> input = new LinkedHashMap<>();

    /**
     * 校验当前提供方调用请求的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的提供方调用请求结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ProviderInvokeRequest validate() {
        ProviderCatalogRequest.requireVersion(protocolVersion);
        ownerKey = required(ownerKey, "ownerKey");
        sessionId = required(sessionId, "sessionId");
        turnId = required(turnId, "turnId");
        toolCallId = required(toolCallId, "toolCallId");
        toolName = required(toolName, "toolName");
        if (!toolName.matches("[a-z][a-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("toolName is invalid");
        }
        input = Map.copyOf(input == null ? Map.of() : input);
        return this;
    }

    /**
     * 生成当前操作所需的required文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前提供方调用请求使用的字段，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String required(String value, String field) {
        value = ProviderCatalogRequest.text(value);
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
