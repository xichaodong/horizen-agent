package dev.horizen.agent.tool.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.provider.spi.gateway.GatewayBackend;
import dev.horizen.agent.provider.spi.gateway.GatewayContext;
import dev.horizen.agent.tool.adapter.ToolAdapterContext;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 面向模型的工具；凭据和网关身份不会出现在模型可见的参数结构中。
 */
public final class GatewayTools {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private GatewayTools() {
    }

    /**
     * 注册网关工具集合。
     *
     * @param toolkit 当前网关工具集合持有的工具集对象，供相应处理步骤使用。
     * @param client  当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    public static void register(Toolkit toolkit, GatewayBackend client) {
        Objects.requireNonNull(toolkit, "toolkit");
        Objects.requireNonNull(client, "client");
        toolkit.registerAgentTool(new GatewayTool(client, false));
        toolkit.registerAgentTool(new GatewayTool(client, true));
    }

    /**
     * 将已声明外部工具注册为 AgentScope 工具的调用适配器。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class GatewayTool implements AgentTool {
        /**
         * 当前适配器使用的远端客户端，供实际网络或服务请求使用。
         */
        private GatewayBackend client;

        /**
         * 调用的状态标记，用于选择当前组件的处理路径。
         */
        private boolean invoke;

        /**
         * 读取名称。
         *
         * @return 本次处理生成或读取的文本。
         */
        @Override
        public String getName() {
            return invoke ? "tool_gateway_invoke" : "tool_gateway_list";
        }

        /**
         * 读取说明。
         *
         * @return 本次处理生成或读取的文本。
         */
        @Override
        public String getDescription() {
            return invoke
                    ? "调用工具网关中的业务工具。先调用 tool_gateway_list 获取工具名称和参数结构。"
                    + "只传业务参数，身份由运行环境提供。尊重 failed、approval_required 等状态，审批未完成代表尚未执行。"
                    : "列出当前会话可使用的业务工具及 tool_input 参数结构。调用业务工具前先查询此目录。";
        }

        /**
         * 读取参数集合。
         *
         * @return 按返回类型约定组织的结果映射。
         */
        @Override
        public Map<String, Object> getParameters() {
            if (!invoke) {
                return Map.of(
                        "type", "object", "properties", Map.of(), "additionalProperties", false);
            }
            return Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "tool_name", Map.of("type", "string", "description", "目录中的工具名称"),
                            "tool_input", Map.of("type", "object", "description", "符合目录参数结构的业务参数")),
                    "required",
                    List.of("tool_name", "tool_input"),
                    "additionalProperties",
                    false);
        }

        /**
         * 判断读取只读。
         *
         * @return 本次检查是否通过或本次更新是否成功。
         */
        @Override
        public boolean isReadOnly() {
            return !invoke;
        }

        /**
         * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
         *
         * @param param 当前网关工具持有的参数对象，供相应处理步骤使用。
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            GatewayContext context =
                    param.getRuntimeContext() == null
                            ? null
                            : GatewayToolAdapter.gatewayContext(
                            ToolAdapterContext.from(param.getRuntimeContext()));
            Map<String, Object> input = param.getInput();
            if (!invoke) {
                return input.isEmpty()
                        ? new GatewayToolAdapter(client).catalogResult(context)
                        : Mono.just(
                        error(
                                "invalid_tool_input",
                                "tool_gateway_list accepts no arguments"));
            }
            if (!input.keySet().equals(Set.of("tool_name", "tool_input"))
                    || !(input.get("tool_name") instanceof String name)
                    || name.isBlank()
                    || !(input.get("tool_input") instanceof Map<?, ?> businessInput)
                    || businessInput.keySet().stream().anyMatch(key -> !(key instanceof String))) {
                return Mono.just(
                        error(
                                "invalid_tool_input",
                                "Expected tool_name (string) and tool_input (object), with no identity fields"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) businessInput;
            String callId =
                    param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
            return new GatewayToolAdapter(client).invokeGateway(context, callId, name, arguments);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayTools处理步骤使用。
     *
     * @param code    当前网关工具集合使用的代码，供其处理与状态记录使用。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @return 本次操作返回的工具结果块结果。
     */
    private static ToolResultBlock error(String code, String message) {
        return ToolResultBlock.error(
                JSON.createObjectNode()
                        .put("status", "failed")
                        .put("errorCode", code)
                        .put("errorMessage", message)
                        .toString());
    }
}
