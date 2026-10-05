package dev.horizen.agent.tool.gateway;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.observability.ExecutionTraceContext;
import dev.horizen.agent.provider.spi.ProviderResultStatus;
import dev.horizen.agent.provider.spi.gateway.GatewayBackend;
import dev.horizen.agent.provider.spi.gateway.GatewayCallerAttributes;
import dev.horizen.agent.provider.spi.gateway.GatewayContext;
import dev.horizen.agent.provider.spi.gateway.GatewayException;
import dev.horizen.agent.provider.spi.gateway.GatewayResult;
import dev.horizen.agent.tool.adapter.ProviderContractMapper;
import dev.horizen.agent.tool.adapter.ToolAdapterContext;
import dev.horizen.agent.tool.adapter.ToolDefinition;
import dev.horizen.agent.tool.adapter.ToolProvider;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 类型化 Provider 传输契约到 AgentScope 的唯一映射入口。 */
public final class GatewayToolAdapter implements ToolProvider {
    /** 外部工具目录与调用的网关适配器。 */
    private final GatewayBackend gateway;

    /**
     * 创建网关工具适配器，初始化该组件所需的状态、配置或依赖。
     *
     * @param gateway 外部工具目录与调用的网关适配器。
     */
    public GatewayToolAdapter(GatewayBackend gateway) {
        this.gateway = Objects.requireNonNull(gateway);
    }

    /**
     * 把 Provider 目录转换成运行时工具定义并保留其治理策略。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
        return Mono.fromCompletionStage(() -> gateway.catalog(gatewayContext(context)))
                .map(
                        catalog ->
                                catalog.validate().getTools().stream()
                                        .map(ProviderContractMapper::toDefinition)
                                        .toList());
    }

    /**
     * 调用网关工具适配器。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param id 目标对象的标识。
     * @param name 需要定位或处理的名称。
     * @param input 本次处理的输入。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> invoke(
            ToolAdapterContext context, String id, String name, Map<String, Object> input) {
        return invokeGateway(gatewayContext(context), id, name, input);
    }

    /**
     * 使用宿主绑定的可信上下文调用外部 Provider，不从模型参数推断访问身份。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param id 目标对象的标识。
     * @param name 需要定位或处理的名称。
     * @param input 本次处理的输入。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Mono<ToolResultBlock> invokeGateway(
            GatewayContext context, String id, String name, Map<String, Object> input) {
        return Mono.fromCompletionStage(() -> gateway.invoke(context, id, name, input))
                .map(GatewayToolAdapter::runtimeResult)
                .onErrorResume(GatewayToolAdapter::failure);
    }

    /** 兼容旧版的模型可见发现信封，仅在运行时边界生成。 */
    public Mono<ToolResultBlock> catalogResult(GatewayContext context) {
        return Mono.fromCompletionStage(() -> gateway.catalog(context))
                .map(
                        catalog -> {
                            var payload =
                                    JsonUtils.newMapper()
                                            .createObjectNode()
                                            .put("protocolVersion", catalog.getProtocolVersion())
                                            .put("status", "success")
                                            .put("tool_count", catalog.getTools().size());
                            gateway.catalogAnnotations()
                                    .forEach(
                                            (name, value) ->
                                                    payload.set(
                                                            name,
                                                            JsonUtils.newMapper()
                                                                    .valueToTree(value)));
                            var tools = payload.putArray("tools");
                            for (var tool : catalog.getTools()) {
                                var entry = JsonUtils.newMapper().valueToTree(tool);
                                var object = (ObjectNode) entry;
                                object.put("tool_name", tool.getName());
                                object.set("tool_input", object.get("inputSchema"));
                                tools.add(object);
                            }
                            return ToolResultBlock.text(payload.toString())
                                    .withState(ToolResultState.SUCCESS);
                        })
                .onErrorResume(GatewayToolAdapter::failure);
    }

    /**
     * 将运行时绑定的归属、执行与调用属性转换成 Provider 调用上下文。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的网关上下文结果。
     */
    public static GatewayContext gatewayContext(ToolAdapterContext context) {
        var attributes = context.getRuntimeContext().get(GatewayCallerAttributes.class);
        var trace = context.getRuntimeContext().get(ExecutionTraceContext.class);
        return new GatewayContext(
                context.getOwnerKey(),
                context.getSessionId(),
                context.getTurnId(),
                context.getOwnerKey().isBlank() || context.getTurnId().isBlank(),
                attributes == null ? Map.of() : attributes.getValues(),
                trace == null ? id -> Map.of() : trace::toolHeaders);
    }

    /**
     * 将 Provider 的状态、正文、呈现与产物引用转换成统一运行时结果。
     *
     * @param result 本次处理已有的结果。
     * @return 本次操作返回的工具结果块结果。
     */
    private static ToolResultBlock runtimeResult(GatewayResult result) {
        String text = JsonUtils.toJson(result.getPayload());
        return result.getStatus() == ProviderResultStatus.ERROR
                ? ToolResultBlock.error(text)
                : ToolResultBlock.text(text)
                        .withState(
                                result.getStatus() == ProviderResultStatus.SUCCESS
                                        ? ToolResultState.SUCCESS
                                        : ToolResultState.DENIED);
    }

    /**
     * 将外部调用失败转换为统一的工具失败结果。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private static Mono<ToolResultBlock> failure(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return Mono.just(
                root instanceof GatewayException failure
                        ? runtimeResult(failure.getResult())
                        : ToolResultBlock.error(
                                JsonUtils.toJson(
                                        Map.of(
                                                "status",
                                                "failed",
                                                "errorCode",
                                                "gateway_request_failed",
                                                "errorMessage",
                                                "Gateway request could not complete; do not automatically retry"))));
    }
}
