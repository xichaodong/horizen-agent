package dev.horizen.agent.observability.horizen;

import lombok.Data;

import java.beans.ConstructorProperties;
import java.util.Map;

/**
 * 应用为一次 Agent 调用提供的身份和关联信息。
 */
@Data
public class HorizenTraceContext {
    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
     */
    private String traceId;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private String sessionId;

    /**
     * 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     */
    private String userId;

    /**
     * 当前HorizenTrace上下文的名称，用于目录、调用或展示中的识别。
     */
    private String name;

    /**
     * 与当前对象关联的附加元数据，不替代领域状态或授权校验。
     */
    private Map<String, Object> metadata;

    /**
     * 创建HorizenTrace上下文，初始化该组件所需的状态、配置或依赖。
     *
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param traceId   关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param name      需要定位或处理的名称。
     * @param metadata  与当前对象关联的附加元数据，不替代领域状态或授权校验。
     */
    @ConstructorProperties({"turnId", "traceId", "sessionId", "userId", "name", "metadata"})
    public HorizenTraceContext(
            String turnId,
            String traceId,
            String sessionId,
            String userId,
            String name,
            Map<String, Object> metadata) {
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);

        this.turnId = turnId;
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.name = name;
        this.metadata = metadata;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的HorizenTrace上下文结果。
     */
    public static HorizenTraceContext of(String turnId, String sessionId) {
        return new HorizenTraceContext(turnId, null, sessionId, null, null, Map.of());
    }
}
