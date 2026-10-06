package dev.horizen.agent.domain.workspace.release;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 校验已发布 Skill 声明，不将运行时 SDK 暴露给用例。
 */
public interface WorkspacePublicationValidator {
    /**
     * 校验当前工作区发布校验器的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @param files 文件集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param read  将输入转换为目标结果的函数。
     * @return 本次处理得到的结果集合。
     */
    Set<String> validate(
            List<WorkspaceCatalogRepository.File> files,
            Function<WorkspaceCatalogRepository.File, byte[]> read);
}
