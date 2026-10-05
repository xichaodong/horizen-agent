package dev.horizen.agent.tools.memory;

import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 对经过整理的长期记忆执行精确匹配更新和删除。 */
public final class MemoryManageTool extends ToolBase {
    /** 当前 Agent 使用的工作区配置或管理入口。 */
    private final WorkspaceManager workspace;

    /** 把跨会话记忆写入持久工作区文档的服务。 */
    private final CloudMemoryService cloudMemory;

    /** 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。 */
    private final String agentKey;

    /**
     * 创建记忆管理工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param workspace 当前记忆管理工具持有的工作区对象，供相应处理步骤使用。
     * @param cloudMemory 提供云端记忆能力的依赖，具体实现由当前组件的组装方传入。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     */
    public MemoryManageTool(
            WorkspaceManager workspace, CloudMemoryService cloudMemory, String agentKey) {
        super(
                ToolBase.builder()
                        .name("memory_manage")
                        .description("更新或遗忘 MEMORY.md 中一条完全匹配的长期记忆。先用 memory_search/get 确认原文。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "current",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "要替换或删除的完整原文"),
                                                "replacement",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "新内容；空字符串表示遗忘")),
                                        "required",
                                        List.of("current", "replacement"),
                                        "additionalProperties",
                                        false))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.workspace = workspace;
        this.cloudMemory = cloudMemory;
        this.agentKey = agentKey;
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前记忆管理工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> manage(param));
    }

    /**
     * 计算或取得本方法声明的结果，供当前MemoryManageTool处理步骤使用。
     *
     * @param param 当前记忆管理工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock manage(ToolCallParam param) {
        Object currentValue = param.getInput() == null ? null : param.getInput().get("current");
        Object replacementValue =
                param.getInput() == null ? null : param.getInput().get("replacement");
        if (!(currentValue instanceof String currentText)
                || currentText.isBlank()
                || !(replacementValue instanceof String replacementText)) {
            return ToolResultBlock.error(
                    "current must be a nonempty string and replacement must be a string");
        }
        String current = currentText.strip();
        String replacement = replacementText.strip();
        if (cloudMemory != null) {
            String owner =
                    param.getRuntimeContext() == null
                            ? null
                            : param.getRuntimeContext().getUserId();
            if (owner == null || owner.isBlank())
                return ToolResultBlock.error("owner identity is required");
            try {
                var scope = param.getRuntimeContext().get(ToolInvocationScope.class);
                cloudMemory.replaceExactLine(
                        owner,
                        agentKey,
                        MemoryOperationId.of(param, owner, agentKey, "memory_manage"),
                        current,
                        replacement,
                        scope.getSessionId(),
                        scope.getTurnId(),
                        param.getToolUseBlock().getId());
            } catch (RuntimeException error) {
                return ToolResultBlock.error(error.getMessage());
            }
            return ToolResultBlock.text(
                    replacement.isBlank() ? "Memory deleted" : "Memory updated");
        }
        String content =
                workspace.readManagedWorkspaceFileUtf8(param.getRuntimeContext(), "MEMORY.md");
        if (content == null || content.isBlank())
            return ToolResultBlock.error("MEMORY.md is empty");
        long matches = content.lines().filter(line -> line.strip().equals(current)).count();
        if (matches != 1)
            return ToolResultBlock.error(
                    "memory_manage requires exactly one matching line; found " + matches);
        StringBuilder updated = new StringBuilder();
        content.lines()
                .forEach(
                        line -> {
                            if (!line.strip().equals(current)) updated.append(line).append('\n');
                            else if (!replacement.isBlank())
                                updated.append(replacement).append('\n');
                        });
        workspace.writeUtf8WorkspaceRelative(
                param.getRuntimeContext(), "MEMORY.md", updated.toString());
        String audit =
                "\n## Memory "
                        + (replacement.isBlank() ? "Delete" : "Update")
                        + " — "
                        + Instant.now()
                        + "\n- Previous: "
                        + current
                        + (replacement.isBlank() ? "" : "\n- Replacement: " + replacement)
                        + "\n";
        workspace.appendUtf8WorkspaceRelative(
                param.getRuntimeContext(), "memory/" + LocalDate.now() + ".md", audit);
        return ToolResultBlock.text(replacement.isBlank() ? "Memory deleted" : "Memory updated");
    }
}
