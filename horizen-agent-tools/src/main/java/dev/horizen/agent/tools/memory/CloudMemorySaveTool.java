package dev.horizen.agent.tools.memory;

import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 为 AgentScope 的本地、后写覆盖式 memory_save 工具提供适用于云端的替代实现。 */
public final class CloudMemorySaveTool extends ToolBase {
    /** 当前归属范围内的记忆读取或写入服务。 */
    private final CloudMemoryService memory;

    /** 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。 */
    private final String agentKey;

    /**
     * 创建云端记忆保存工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param memory 提供记忆能力的依赖，具体实现由当前组件的组装方传入。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     */
    public CloudMemorySaveTool(CloudMemoryService memory, String agentKey) {
        super(
                ToolBase.builder()
                        .name("memory_save")
                        .description("保存长期记忆。云端工作区会原子追加，并对同一工具调用重试去重。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "content",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "要记住的 Markdown 要点")),
                                        "required",
                                        List.of("content"),
                                        "additionalProperties",
                                        false))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.memory = Objects.requireNonNull(memory, "memory");
        this.agentKey = Objects.requireNonNull(agentKey, "agentKey");
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前云端记忆保存工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> save(param));
    }

    /**
     * 保存云端记忆保存工具。
     *
     * @param param 当前云端记忆保存工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock save(ToolCallParam param) {
        Object value = param.getInput() == null ? null : param.getInput().get("content");
        if (!(value instanceof String text) || text.isBlank())
            return ToolResultBlock.error("content must be a nonempty string");
        String content = text.strip();
        String owner =
                param.getRuntimeContext() == null ? null : param.getRuntimeContext().getUserId();
        if (owner == null || owner.isBlank())
            return ToolResultBlock.error("owner identity is required");
        try {
            var scope = param.getRuntimeContext().get(ToolInvocationScope.class);
            memory.save(
                    owner,
                    agentKey,
                    MemoryOperationId.of(param, owner, agentKey, "memory_save"),
                    content,
                    scope.getSessionId(),
                    scope.getTurnId(),
                    param.getToolUseBlock().getId());
        } catch (RuntimeException error) {
            return ToolResultBlock.error(error.getMessage());
        }
        long count = content.lines().filter(line -> line.stripLeading().startsWith("-")).count();
        if (count == 0) count = 1;
        return ToolResultBlock.text(
                "Saved " + count + " memor" + (count == 1 ? "y" : "ies") + " to MEMORY.md");
    }
}
