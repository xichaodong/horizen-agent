package dev.horizen.agent.tool.adapter;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/** 展示位置由 Provider 声明，Horizen 不解读分组或 Skill 名称。 */
@Getter
@EqualsAndHashCode
public final class ToolGroupDefinition {
    /** 默认分组标识使用的固定标识或协议文本。 */
    public static final String DEFAULT_GROUP_ID = "external";

    /** 当前工具分组定义的定位标识。 */
    private final String id;

    /** 当前工具分组定义的用途说明，供目录或配置阅读者理解。 */
    private final String description;

    /** 活跃按条件默认的状态标记，用于选择当前组件的处理路径。 */
    private final boolean activeByDefault;

    /** 是否允许通过匹配 Skill 激活当前工具组。 */
    private final String activateOnSkill;

    /**
     * 创建工具分组定义，初始化该组件所需的状态、配置或依赖。
     *
     * @param id 目标对象的标识。
     * @param description 当前工具分组定义的用途说明，供目录或配置阅读者理解。
     * @param activeByDefault 活跃按条件默认的状态标记，用于选择当前组件的处理路径。
     * @param activateOnSkill 当前工具分组定义使用的activate响应Skill，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ToolGroupDefinition(
            String id, String description, boolean activeByDefault, String activateOnSkill) {
        id = id == null || id.isBlank() ? DEFAULT_GROUP_ID : id.trim();
        if (!id.matches("[a-z][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("tool group id is invalid");
        }
        this.id = id;
        this.description = description == null ? "" : description.trim();
        this.activeByDefault = activeByDefault;
        this.activateOnSkill = activateOnSkill == null ? "" : activateOnSkill.trim();
        if (!this.activateOnSkill.isEmpty() && activeByDefault) {
            throw new IllegalArgumentException(
                    "Skill-activated tool group cannot be active by default");
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的工具分组定义结果。
     */
    public static ToolGroupDefinition externalDefault() {
        return new ToolGroupDefinition(DEFAULT_GROUP_ID, "外部 Provider 工具", true, "");
    }
}
