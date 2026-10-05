package dev.horizen.agent.provider.spi.gateway;

import dev.horizen.agent.provider.spi.ProviderCatalogResponse;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/** 异步 Provider 接口，不依赖 HTTP、AgentScope 或 Reactor 类型。 */
public interface GatewayBackend {
    /**
     * 计算或取得本方法声明的结果，供当前GatewayBackend处理步骤使用。
     *
     * @return 按返回类型约定组织的结果映射。
     */
    default Map<String, Object> catalogAnnotations() {
        return Map.of();
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayBackend处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    CompletionStage<ProviderCatalogResponse> catalog(GatewayContext context);

    /**
     * 调用网关后端。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param toolName 可调用工具的注册名称，须与目录中声明的名称一致。
     * @param input 本次处理的输入。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    CompletionStage<GatewayResult> invoke(
            GatewayContext context, String toolCallId, String toolName, Map<String, Object> input);
}
