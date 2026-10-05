package dev.horizen.agent.tool.governance;

import lombok.Getter;

import java.util.Set;

/** 当前 Turn 内，动态适配器目录授权的业务工具并集。 */
@Getter
public final class AdapterToolAuthorization {
    /** 工具名称集合的去重集合，供成员查找或范围检查使用。 */
    private final Set<String> toolNames;

    /**
     * 创建适配器工具授权，初始化该组件所需的状态、配置或依赖。
     *
     * @param toolNames 工具名称集合的去重集合，供成员查找或范围检查使用。
     */
    public AdapterToolAuthorization(Set<String> toolNames) {
        this.toolNames = Set.copyOf(toolNames);
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
