package dev.horizen.agent.tool.adapter;

import io.agentscope.core.message.ToolResultBlock;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 运行时内部响应式 Provider 接口。
 *
 * <p>外部适配器面向 {@code horizen-agent-provider-spi}，由传输层将该版本化契约映射为此依赖
 * AgentScope 的接口。
 */
public interface ToolProvider {
    /**
     * 查询列表中的工具提供方。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    Mono<List<ToolDefinition>> list(ToolAdapterContext context);

    /**
     * 调用工具提供方。
     *
     * @param context    当前执行上下文，提供关联标识和宿主绑定信息。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param toolName   可调用工具的注册名称，须与目录中声明的名称一致。
     * @param input      本次处理的输入。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    Mono<ToolResultBlock> invoke(
            ToolAdapterContext context,
            String toolCallId,
            String toolName,
            Map<String, Object> input);
}
