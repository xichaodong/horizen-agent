package dev.horizen.agent.adapter.agentscope.workspace.release;

import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;
import dev.horizen.agent.domain.workspace.release.WorkspacePublicationValidator;

import io.agentscope.core.skill.util.SkillUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 使用 AgentScope 声明解析器；目录完整性仍由发布规则保证。 */
public final class AgentScopePublicationValidator implements WorkspacePublicationValidator {
    /**
     * 校验当前Agent作用域发布校验器的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @param files 文件集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param read 将输入转换为目标结果的函数。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Set<String> validate(
            List<WorkspaceCatalogRepository.File> files,
            Function<WorkspaceCatalogRepository.File, byte[]> read) {
        Set<String> roots = new HashSet<>();
        Set<String> declarations = new HashSet<>();
        for (var file : files)
            if (file.getPath().startsWith("skills/")) {
                String[] segments = file.getPath().split("/", 3);
                if (segments.length != 3 || !segments[1].matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
                    throw new IllegalArgumentException("Invalid Skill directory");
                roots.add(segments[1]);
                if ("SKILL.md".equals(segments[2])) {
                    var skill =
                            SkillUtil.createFrom(
                                    new String(read.apply(file), StandardCharsets.UTF_8),
                                    Map.of(),
                                    "workspace");
                    if (!segments[1].equals(skill.getName()))
                        throw new IllegalArgumentException(
                                "SKILL.md name must match its directory");
                    declarations.add(segments[1]);
                }
            }
        if (roots.size() > 100 || !roots.equals(declarations))
            throw new IllegalArgumentException(
                    "Every Skill directory must contain SKILL.md (maximum 100)");
        return roots;
    }
}
