package dev.horizen.agent.runtime.skill;

import dev.horizen.agent.skill.SkillReleaseSnapshot;

import lombok.Data;

import java.util.Objects;

/** 请求级发布绑定，同一 Turn 的所有推理步骤共用。 */
@Data
public final class SkillReleaseContext {
    /** 本次操作持有的不可变内容或状态快照。 */
    private final SkillReleaseSnapshot snapshot;

    /**
     * 创建Skill发布上下文，初始化该组件所需的状态、配置或依赖。
     *
     * @param snapshot 当前Skill发布上下文持有的快照对象，供相应处理步骤使用。
     */
    public SkillReleaseContext(SkillReleaseSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }
}
