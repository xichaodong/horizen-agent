package dev.horizen.agent.tool.governance;

import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 模型调用前捕获的不可变、模型可见工具集合。
 */
@Getter
public final class ToolViewSnapshot {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private final String ownerKey;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private final String sessionId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private final String turnId;

    /**
     * 记录版本，用于乐观并发控制或区分协议版本。
     */
    private final String version;

    /**
     * 当前记录的创建时间。
     */
    private final Instant createdAt;

    /**
     * 工具名称集合的去重集合，供成员查找或范围检查使用。
     */
    private final Set<String> toolNames;

    /**
     * 创建工具视图快照，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param version   记录版本，用于乐观并发控制或区分协议版本。
     * @param createdAt 当前记录的创建时间。
     * @param toolNames 工具名称集合的去重集合，供成员查找或范围检查使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ToolViewSnapshot(
            String ownerKey,
            String sessionId,
            String turnId,
            String version,
            Instant createdAt,
            Set<String> toolNames) {
        this.ownerKey = ownerKey == null ? "" : ownerKey;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.turnId = turnId == null ? "" : turnId;
        if (version == null || version.isBlank())
            throw new IllegalArgumentException("version required");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.toolNames = Set.copyOf(Objects.requireNonNull(toolNames, "toolNames"));
    }

    /**
     * 检查allows对应的条件，供调用方选择后续处理分支。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean allows(String name) {
        return toolNames.contains(name);
    }
}
