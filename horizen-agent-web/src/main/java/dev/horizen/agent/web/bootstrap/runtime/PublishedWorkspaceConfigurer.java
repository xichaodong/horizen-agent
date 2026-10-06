package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.adapter.agentscope.workspace.release.PublicationStateMiddleware;
import dev.horizen.agent.web.config.E2bSandboxProperties;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已发布子 Agent 的校验、工具限制和不可变工作区路由。
 */
final class PublishedWorkspaceConfigurer {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private PublishedWorkspaceConfigurer() {
    }

    /**
     * 计算或取得本方法声明的结果，供当前PublishedWorkspaceConfigurer处理步骤使用。
     *
     * @param builder            当前已发布工作区配置器持有的构造器对象，供相应处理步骤使用。
     * @param publishedWorkspace 当前已发布工作区配置器持有的已发布工作区对象，供相应处理步骤使用。
     * @param sandboxProperties  当前已发布工作区配置器持有的沙箱配置对象，供相应处理步骤使用。
     * @param toolkit            当前已发布工作区配置器持有的工具集对象，供相应处理步骤使用。
     * @return 按返回类型约定组织的结果映射。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static Map<String, Set<String>> configure(
            HarnessAgent.Builder builder,
            Path publishedWorkspace,
            E2bSandboxProperties sandboxProperties,
            Toolkit toolkit) {
        Map<String, Set<String>> childTools = new LinkedHashMap<>();
        if (publishedWorkspace != null) {
            builder.disableMemoryHooks();
            builder.disableTranscript();
            Path declarations = publishedWorkspace.resolve("subagents");
            if (Files.isDirectory(declarations)) {
                try (var files = Files.list(declarations)) {
                    for (Path file : files.toList()) {
                        var declaration = AgentSpecLoader.loadFromFile(file, publishedWorkspace);
                        if (declaration == null
                                || declaration.getWorkspacePath() != null
                                || declaration.getModel() != null
                                || declaration.getWorkspaceMode() != WorkspaceMode.SHARED)
                            throw new IllegalArgumentException(
                                    "Invalid published subagent workspace or model");
                        if (!declaration.getTools().isEmpty())
                            childTools.put(
                                    declaration.getName(), Set.copyOf(declaration.getTools()));
                    }
                } catch (IOException error) {
                    throw new IllegalStateException("Cannot read published subagents", error);
                }
            }
            builder.middleware(
                    new PublicationStateMiddleware(
                            sandboxProperties.isEnabled()
                                    ? sandboxProperties.getWorkspaceRoot()
                                    : null,
                            toolkit.getActiveGroups(),
                            childTools));
            builder.disableDefaultWorkspaceSkills();
            builder.filesystemRoute(
                    "AGENTS.md",
                    WorkspaceRuntimeConfigurer.publicationFilesystem(publishedWorkspace));
            for (String segment : List.of("knowledge", "subagents", "skills", ".skills-cache")) {
                builder.filesystemRoute(
                        segment + "/",
                        WorkspaceRuntimeConfigurer.publicationFilesystem(
                                publishedWorkspace.resolve(segment)));
            }
        }

        return childTools;
    }
}
