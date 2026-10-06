package dev.horizen.agent.tool.adapter;

import io.agentscope.core.agent.RuntimeContext;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 向 {@link ToolProvider} 提供当前 Turn 的可信宿主上下文。
 */
@Data
@NoArgsConstructor
public class ToolAdapterContext {
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
     * AgentScope 当前调用上下文，携带隔离身份和类型化宿主绑定。
     */
    private RuntimeContext runtimeContext;

    /**
     * 创建工具适配器上下文，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey       宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId      会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId         单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param runtimeContext 当前工具适配器上下文持有的运行时上下文对象，供相应处理步骤使用。
     */
    public ToolAdapterContext(
            String ownerKey, String sessionId, String turnId, RuntimeContext runtimeContext) {
        this.ownerKey = ownerKey == null ? "" : ownerKey;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.turnId = turnId == null ? "" : turnId;
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtimeContext");
    }

    /**
     * 从输入构造工具适配器上下文。
     *
     * @param runtimeContext 当前工具适配器上下文持有的运行时上下文对象，供相应处理步骤使用。
     * @return 本次操作返回的工具适配器上下文结果。
     */
    public static ToolAdapterContext from(RuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext");
        ToolInvocationScope scope = runtimeContext.get(ToolInvocationScope.class);
        return scope == null
                ? new ToolAdapterContext(
                runtimeContext.getUserId(),
                runtimeContext.getSessionId(),
                "",
                runtimeContext)
                : new ToolAdapterContext(
                scope.getOwnerKey(),
                scope.getSessionId(),
                scope.getTurnId(),
                runtimeContext);
    }
}
