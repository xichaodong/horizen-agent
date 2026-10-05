package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 分组元数据由 Provider 管理，Horizen 不根据名称推断语义。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolGroupContract {
    /** 当前工具分组契约的定位标识。 */
    private String id = "external";

    /** 当前工具分组契约的用途说明，供目录或配置阅读者理解。 */
    private String description = "";

    /** 活跃按条件默认的状态标记，用于选择当前组件的处理路径。 */
    private boolean activeByDefault = true;

    /** 是否允许通过匹配 Skill 激活当前工具组。 */
    private String activateOnSkill = "";

    /**
     * 校验当前工具分组契约的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的工具分组契约结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ToolGroupContract validate() {
        if (id == null || !id.matches("[a-z][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("tool group id is invalid");
        }
        if (activateOnSkill == null) activateOnSkill = "";
        if (!activateOnSkill.isBlank() && activeByDefault) {
            throw new IllegalArgumentException("Skill-activated group cannot be active by default");
        }
        if (description == null) description = "";
        return this;
    }
}
