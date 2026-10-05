package dev.horizen.agent.adapter.skill.horizen;

import dev.horizen.agent.runtime.skill.SkillReleaseContext;
import dev.horizen.agent.skill.SkillReleaseSnapshot;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.RuntimeContextSkillRepository;
import io.agentscope.harness.agent.skill.SkillResources;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 将请求固定的领域发布版本适配为 AgentScope 的只读仓储契约。 */
public final class AgentScopeSkillRepositoryAdapter
        implements RuntimeContextSkillRepository, LazyResourceCapable {
    /** 来源使用的固定标识或协议文本。 */
    private static final String SOURCE = "horizen";

    /**
     * 读取全部Skill集合。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<AgentSkill> getAllSkills(RuntimeContext context) {
        SkillReleaseSnapshot snapshot = snapshot(context);
        return snapshot.skillNames().stream().map(name -> agentSkill(snapshot, name)).toList();
    }

    /**
     * 读取全部Skill集合。
     *
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<AgentSkill> getAllSkills() {
        return List.of(); // 未指定作用域的读取不能暴露其他执行的发布版本。
    }

    /**
     * 读取Skill。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次操作返回的AgentSkill结果。
     */
    @Override
    public AgentSkill getSkill(String name) {
        return null;
    }

    /**
     * 读取全部Skill名称集合。
     *
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<String> getAllSkillNames() {
        return getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param skillName 当前Agent作用域Skill仓储适配器使用的Skill名称，供其处理与状态记录使用。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的Skill资源集合结果。
     */
    @Override
    public SkillResources resourcesFor(String skillName, RuntimeContext context) {
        SkillReleaseSnapshot release = snapshot(context);
        return new SkillResources() {
            /**
             * 读取匿名实现。
             *
             * @param relativePath 相对于工作区根目录的资源路径，不能借此越过根目录。
             * @return 可用结果；没有可用对象时以空 Optional 表示。
             */
            @Override
            public Optional<String> read(String relativePath) {
                return release.read(skillName, relativePath);
            }

            /**
             * 读取二进制。
             *
             * @param relativePath 相对于工作区根目录的资源路径，不能借此越过根目录。
             * @return 可用结果；没有可用对象时以空 Optional 表示。
             */
            @Override
            public Optional<byte[]> readBinary(String relativePath) {
                return release.readBinary(skillName, relativePath);
            }

            /**
             * 查询列表中的匿名实现。
             *
             * @return 本次处理得到的结果集合。
             */
            @Override
            public List<String> list() {
                return release.listResources(skillName);
            }
        };
    }

    /**
     * 保存Agent作用域Skill仓储适配器。
     *
     * @param skills Skill集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param force force的状态标记，用于选择当前组件的处理路径。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        return false;
    }

    /**
     * 删除Agent作用域Skill仓储适配器。
     *
     * @param skillName 当前Agent作用域Skill仓储适配器使用的Skill名称，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean delete(String skillName) {
        return false;
    }

    /**
     * 检查skillExists对应的条件，供调用方选择后续处理分支。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean skillExists(String name) {
        return getSkill(name) != null;
    }

    /**
     * 读取仓储Info。
     *
     * @return 本次操作返回的AgentSkill仓储Info结果。
     */
    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("horizen-release", "active-release", false);
    }

    /**
     * 读取来源。
     *
     * @return {@link #SOURCE} 中保存的值。
     */
    @Override
    public String getSource() {
        return SOURCE;
    }

    /**
     * 设置Writeable。
     *
     * @param writeable writeable的状态标记，用于选择当前组件的处理路径。
     * @throws UnsupportedOperationException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void setWriteable(boolean writeable) {
        if (writeable) {
            throw new UnsupportedOperationException("Horizen release repository is read-only");
        }
    }

    /**
     * 判断Writeable。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean isWriteable() {
        return false;
    }

    /**
     * 读取快照中的Agent作用域Skill仓储适配器。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的Skill发布快照结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static SkillReleaseSnapshot snapshot(RuntimeContext context) {
        SkillReleaseContext release =
                context == null ? null : context.get(SkillReleaseContext.class);
        if (release == null) {
            throw new IllegalStateException("No Skill release is bound to this agent call");
        }
        return release.getSnapshot();
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentScopeSkillRepositoryAdapter处理步骤使用。
     *
     * @param snapshot 当前Agent作用域Skill仓储适配器持有的快照对象，供相应处理步骤使用。
     * @param name 需要定位或处理的名称。
     * @return 本次操作返回的AgentSkill结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static AgentSkill agentSkill(SkillReleaseSnapshot snapshot, String name) {
        String markdown =
                snapshot.markdown(name)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Skill content is missing: " + name));
        AgentSkill skill = SkillUtil.createFrom(markdown, Map.of(), SOURCE);
        if (!name.equals(skill.getName())) {
            throw new IllegalStateException("Skill name differs from release runtimeName: " + name);
        }
        return skill;
    }
}
