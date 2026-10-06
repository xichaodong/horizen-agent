package dev.horizen.agent.adapter.gateway.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.provider.codec.ProviderCatalogCodec;
import dev.horizen.agent.provider.spi.ProviderCatalogResponse;
import dev.horizen.agent.provider.spi.ProviderProtocol;
import dev.horizen.agent.provider.spi.ProviderResultStatus;
import dev.horizen.agent.provider.spi.gateway.GatewayBackend;
import dev.horizen.agent.provider.spi.gateway.GatewayContext;
import dev.horizen.agent.provider.spi.gateway.GatewayResult;

import io.agentscope.core.tool.ToolValidator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 从宿主指定的样本文件读取离线、不可变的响应，不会执行任何真实业务操作。
 */
public final class FixtureGateway implements GatewayBackend {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * NOTICE使用的固定标识或协议文本。
     */
    private static final String NOTICE = "模拟数据，仅用于测试 Skill 流程；未查询真实业务或执行任何操作。";

    /**
     * 工具集合的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<String, FixtureTool> tools;

    /**
     * 创建样本网关，初始化该组件所需的状态、配置或依赖。
     *
     * @param fixtureFile 当前样本网关持有的样本文件对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public FixtureGateway(Path fixtureFile) {
        Objects.requireNonNull(fixtureFile, "fixtureFile");
        JsonNode root;
        try (var input = Files.newInputStream(fixtureFile)) {
            root = JSON.readTree(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Cannot read mock gateway fixture file: " + fixtureFile, exception);
        }
        if (root == null || !root.path("tools").isArray()) {
            throw invalidFixture("root must contain a tools array");
        }
        Map<String, FixtureTool> definitions = new LinkedHashMap<>();
        for (JsonNode entry : root.path("tools")) {
            if (!entry.path("name").isTextual() || entry.path("name").asText().isBlank()) {
                throw invalidFixture("each tool must have a nonblank name");
            }
            String name = entry.path("name").asText();
            JsonNode schema = entry.path("inputSchema");
            if (!schema.isObject() || !"object".equals(schema.path("type").asText())) {
                throw invalidFixture("tool " + name + " must have an object inputSchema");
            }
            rejectSchemaReferences(schema);
            if (!entry.has("response")) {
                throw invalidFixture("tool " + name + " is missing its response");
            }
            Map<String, Object> inputSchema = JSON.convertValue(schema, new TypeReference<>() {
            });
            ObjectNode metadata = JSON.createObjectNode();
            for (String field :
                    Set.of(
                            "readOnly",
                            "timeoutSeconds",
                            "idempotent",
                            "concurrencySafe",
                            "supportsCancellation",
                            "approvalPolicy",
                            "group")) {
                if (entry.has(field)) metadata.set(field, entry.get(field).deepCopy());
            }
            FixtureTool definition =
                    new FixtureTool(
                            name,
                            entry.path("description").asText(""),
                            Map.copyOf(inputSchema),
                            entry.path("riskLevel").asText(""),
                            entry.path("requiresApproval").asBoolean(false),
                            metadata,
                            entry.get("response").deepCopy());
            if (definitions.putIfAbsent(name, definition) != null) {
                throw invalidFixture("duplicate tool name: " + name);
            }
        }
        tools = Collections.unmodifiableMap(definitions);
    }

    /**
     * 计算或取得本方法声明的结果，供当前FixtureGateway处理步骤使用。
     *
     * @return 按返回类型约定组织的结果映射。
     */
    @Override
    public Map<String, Object> catalogAnnotations() {
        return Map.of("mock", true, "data_source", "mock", "notice", NOTICE);
    }

    /**
     * 计算或取得本方法声明的结果，供当前FixtureGateway处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public CompletableFuture<ProviderCatalogResponse> catalog(GatewayContext context) {
        return CompletableFuture.supplyAsync(
                () -> {
                    ObjectNode result = envelope("success").put("tool_count", tools.size());
                    ArrayNode catalog = result.putArray("tools");
                    tools.values()
                            .forEach(
                                    tool -> {
                                        ObjectNode entry =
                                                catalog.addObject()
                                                        .put("tool_name", tool.getName())
                                                        .put("description", tool.getDescription());
                                        entry.set(
                                                "tool_input",
                                                JSON.valueToTree(tool.getInputSchema()));
                                        if (!tool.getRiskLevel().isBlank()) {
                                            entry.put("riskLevel", tool.getRiskLevel());
                                        }
                                        if (tool.isRequiresApproval()) {
                                            entry.put("requiresApproval", true);
                                        }
                                        tool.getMetadata()
                                                .fields()
                                                .forEachRemaining(
                                                        field ->
                                                                entry.set(
                                                                        field.getKey(),
                                                                        field.getValue()
                                                                                .deepCopy()));
                                    });
                    return decodeCatalog(catalog);
                });
    }

    /**
     * 调用样本网关。
     *
     * @param context    当前执行上下文，提供关联标识和宿主绑定信息。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param toolName   可调用工具的注册名称，须与目录中声明的名称一致。
     * @param input      本次处理的输入。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public CompletableFuture<GatewayResult> invoke(
            GatewayContext context, String toolCallId, String toolName, Map<String, Object> input) {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (toolName == null || toolName.isBlank() || input == null) {
                        return error("invalid_tool_input", "tool_name and tool_input are required");
                    }
                    FixtureTool tool = tools.get(toolName);
                    if (tool == null) {
                        return error(
                                "unsupported_mock_tool",
                                "This tool has no configured mock response; no action was executed");
                    }
                    String validation =
                            ToolValidator.validateInput(
                                    JSON.valueToTree(input).toString(), tool.getInputSchema());
                    if (validation != null) {
                        return error("schema_validation_failed", validation);
                    }
                    ObjectNode result = envelope("success").put("tool_name", toolName);
                    result.set("safeResult", tool.getResponse().deepCopy());
                    return result(ProviderResultStatus.SUCCESS, result);
                });
    }

    /**
     * 解码目录。
     *
     * @param tools 工具集合的索引映射，供按键查找或归并当前组件的数据。
     * @return 本次操作返回的提供方目录响应结果。
     */
    private static ProviderCatalogResponse decodeCatalog(JsonNode tools) {
        return ProviderCatalogCodec.decode(tools);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param body   当前样本网关持有的正文对象，供相应处理步骤使用。
     * @return 本次操作返回的网关结果结果。
     */
    private static GatewayResult result(ProviderResultStatus status, JsonNode body) {
        return new GatewayResult(
                status, JSON.convertValue(body, new TypeReference<Map<String, Object>>() {
        }));
    }

    /**
     * 计算或取得本方法声明的结果，供当前FixtureGateway处理步骤使用。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @return 本次操作返回的对象节点结果。
     */
    private static ObjectNode envelope(String status) {
        return JSON.createObjectNode()
                .put("protocolVersion", ProviderProtocol.CURRENT_VERSION)
                .put("status", status)
                .put("mock", true)
                .put("data_source", "mock")
                .put("notice", NOTICE);
    }

    /**
     * 计算或取得本方法声明的结果，供当前FixtureGateway处理步骤使用。
     *
     * @param code    当前样本网关使用的代码，供其处理与状态记录使用。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @return 本次操作返回的网关结果结果。
     */
    private static GatewayResult error(String code, String message) {
        return result(
                ProviderResultStatus.ERROR,
                envelope("failed").put("errorCode", code).put("errorMessage", message));
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @return 本次操作返回的Illegal参数异常结果。
     */
    private static IllegalArgumentException invalidFixture(String message) {
        return new IllegalArgumentException("Invalid mock gateway fixture: " + message);
    }

    /**
     * 完成当前操作的rejectSchemaReferences步骤，按实现更新相应状态或依赖。
     *
     * @param node 当前样本网关持有的节点对象，供相应处理步骤使用。
     */
    private static void rejectSchemaReferences(JsonNode node) {
        if (node.isObject()) {
            if (node.has("$ref") || node.has("$dynamicRef") || node.has("$schema")) {
                throw invalidFixture(
                        "schema references and custom dialects are not supported in offline fixtures");
            }
            node.elements().forEachRemaining(FixtureGateway::rejectSchemaReferences);
        } else if (node.isArray()) {
            node.forEach(FixtureGateway::rejectSchemaReferences);
        }
    }

    /**
     * 样本网关内部的样本工具，封装该步骤需要的状态或输入输出。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class FixtureTool {
        /**
         * 当前样本工具的名称，用于目录、调用或展示中的识别。
         */
        private String name;

        /**
         * 当前样本工具的用途说明，供目录或配置阅读者理解。
         */
        private String description;

        /**
         * 工具输入 JSON Schema，供参数校验与模型工具声明使用。
         */
        private Map<String, Object> inputSchema;

        /**
         * 工具目录声明的风险级别，供宿主执行治理使用。
         */
        private String riskLevel;

        /**
         * requires审批的状态标记，用于选择当前组件的处理路径。
         */
        private boolean requiresApproval;

        /**
         * 与当前对象关联的附加元数据，不替代领域状态或授权校验。
         */
        private ObjectNode metadata;

        /**
         * 本次协议调用或固定样本产生的返回结果。
         */
        private JsonNode response;
    }
}
