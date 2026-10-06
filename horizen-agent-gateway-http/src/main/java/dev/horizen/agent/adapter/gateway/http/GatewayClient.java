package dev.horizen.agent.adapter.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.http.BoundedBodyHandlers;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.provider.codec.ProviderCatalogCodec;
import dev.horizen.agent.provider.spi.ProviderCatalogResponse;
import dev.horizen.agent.provider.spi.ProviderProtocol;
import dev.horizen.agent.provider.spi.ProviderResultStatus;
import dev.horizen.agent.provider.spi.gateway.GatewayBackend;
import dev.horizen.agent.provider.spi.gateway.GatewayContext;
import dev.horizen.agent.provider.spi.gateway.GatewayException;
import dev.horizen.agent.provider.spi.gateway.GatewayResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 通用远程 Tool Provider 客户端；调用失败不自动重试，重定向时也不转发凭据。
 */
public final class GatewayClient implements GatewayBackend {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 受保护字段集合的固定取值，用于相应策略和边界判断。
     */
    private static final Set<String> PROTECTED_FIELDS =
            Set.of(
                    "runid",
                    "sessionid",
                    "turnid",
                    "ownerkey",
                    "tenantid",
                    "userid",
                    "callerid",
                    "optid",
                    "shopid",
                    "merchantid",
                    "traceid",
                    "spanid",
                    "parenttraceid",
                    "parentspanid",
                    "invocationid",
                    "traceparent",
                    "businesssubjectid",
                    "businesssubjecttype",
                    "callerattributes",
                    "ucid",
                    "authorization",
                    "token",
                    "accesstoken");

    /**
     * 密钥字段集合的固定取值，用于相应策略和边界判断。
     */
    private static final Set<String> SECRET_FIELDS =
            Set.of("authorization", "token", "accesstoken");

    /**
     * 远端服务的基础地址，用于拼接接口路径。
     */
    private final URI baseUrl;

    /**
     * 服务访问令牌，由宿主配置提供，用于请求认证。
     */
    private final String token;

    /**
     * 超时的时间配置，供等待、调度或失效判断使用。
     */
    private final Duration timeout;

    /**
     * 宿主允许调用的工具名称集合，供目录过滤与执行治理使用。
     */
    private final Set<String> allowedTools;

    /**
     * 用于实际网络请求的共享 HTTP 客户端。
     */
    private final HttpClient http;

    /**
     * 创建网关客户端，初始化该组件所需的状态、配置或依赖。
     *
     * @param baseUrl 远端服务的基础地址，用于拼接接口路径。
     * @param token   服务访问令牌，由宿主配置提供，用于请求认证。
     * @param timeout 本次等待允许持续的最长时间。
     */
    public GatewayClient(URI baseUrl, String token, Duration timeout) {
        this(baseUrl, token, timeout, Set.of());
    }

    /**
     * 空白名单表示工具授权完全交给网关当前目录和策略决定。
     */
    public GatewayClient(URI baseUrl, String token, Duration timeout, Set<String> allowedTools) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (!("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Gateway base URL must be an HTTP(S) URL without credentials, query or fragment");
        }
        if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException(
                    "Gateway token must be configured without line breaks");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Gateway timeout must be positive");
        }
        this.baseUrl = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/");
        this.token = token.trim();
        this.timeout = timeout;
        this.allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayClient处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public CompletableFuture<ProviderCatalogResponse> catalog(GatewayContext context) {
        if (context == null)
            return CompletableFuture.failedFuture(new GatewayException(missingContext()));
        return catalogJson(context).thenApply(ProviderCatalogCodec::decode);
    }

    /**
     * 调用网关客户端。
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
        if (context == null || !context.hasInvocationScope())
            return CompletableFuture.completedFuture(missingContext());
        if (toolName == null || toolName.isBlank() || input == null)
            return CompletableFuture.completedFuture(
                    error("invalid_tool_input", "tool_name and tool_input are required"));
        if (!isAllowed(toolName))
            return CompletableFuture.completedFuture(
                    error("tool_not_allowed", "Tool is not enabled by the host allowlist"));
        String callId =
                toolCallId == null || toolCallId.isBlank()
                        ? UUID.randomUUID().toString()
                        : toolCallId;
        JsonNode arguments = JSON.valueToTree(input);
        if (hasProtectedField(arguments))
            return CompletableFuture.completedFuture(
                    error(
                            "protected_tool_input",
                            "Identity and authorization fields are supplied by the host and must not appear in"
                                    + " tool_input"));
        return catalogJson(context)
                .thenCompose(
                        tools -> {
                            boolean available = false;
                            for (JsonNode tool : tools)
                                available |= toolName.equals(tool.path("tool_name").asText());
                            if (!available)
                                return CompletableFuture.completedFuture(
                                        error(
                                                "tool_not_available",
                                                "Tool is not available in the current gateway catalog"));
                            ObjectNode request =
                                    contextBody(context)
                                            .put("toolCallId", callId)
                                            .put("toolName", toolName);
                            request.set("input", arguments);
                            return post("tools/invoke", request, context)
                                    .thenApply(this::invocationResult);
                        })
                .exceptionally(this::failure);
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayClient处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws GatewayException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private CompletableFuture<ArrayNode> catalogJson(GatewayContext context) {
        return post("tools/list", contextBody(context), context)
                .thenApply(
                        body -> {
                            int protocolVersion =
                                    body.path("protocolVersion")
                                            .asInt(ProviderProtocol.CURRENT_VERSION);
                            if (protocolVersion != ProviderProtocol.CURRENT_VERSION) {
                                throw new GatewayException(
                                        error(
                                                "provider_protocol_version_unsupported",
                                                "Unsupported Provider protocolVersion: "
                                                        + protocolVersion));
                            }
                            if (body.hasNonNull("status")
                                    && !"success".equalsIgnoreCase(body.path("status").asText())) {
                                throw new GatewayException(invocationResult(body));
                            }
                            if (!body.path("tools").isArray()) {
                                throw new GatewayException(
                                        error(
                                                "gateway_invalid_catalog",
                                                "Gateway response is missing the tools array"));
                            }
                            ArrayNode tools = JSON.createArrayNode();
                            for (JsonNode entry : body.path("tools")) {
                                String name =
                                        entry.path("name")
                                                .asText(entry.path("tool_name").asText(""));
                                if (name.isBlank()) {
                                    throw new GatewayException(
                                            error(
                                                    "gateway_invalid_catalog",
                                                    "Gateway catalog contains a tool without a name"));
                                }
                                if (!isAllowed(name)) {
                                    continue;
                                }
                                JsonNode schema =
                                        entry.hasNonNull("inputSchema")
                                                ? entry.get("inputSchema")
                                                : entry.hasNonNull("input_schema")
                                                ? entry.get("input_schema")
                                                : entry.get("tool_input");
                                try {
                                    if (schema != null && schema.isTextual()) {
                                        schema = JSON.readTree(schema.asText());
                                    }
                                } catch (JsonProcessingException exception) {
                                    throw new GatewayException(
                                            error(
                                                    "gateway_invalid_catalog",
                                                    "Gateway tool input schema is not valid JSON"));
                                }
                                if (schema == null || !schema.isObject()) {
                                    throw new GatewayException(
                                            error(
                                                    "gateway_invalid_catalog",
                                                    "Gateway tool input schema must be an object"));
                                }
                                ObjectNode tool =
                                        tools.addObject()
                                                .put("tool_name", name)
                                                .put(
                                                        "description",
                                                        entry.path("description").asText(""));
                                tool.set("tool_input", schema);
                                if (entry.has("riskLevel")) {
                                    tool.set("riskLevel", entry.get("riskLevel"));
                                }
                                if (entry.has("requiresApproval")) {
                                    tool.set("requiresApproval", entry.get("requiresApproval"));
                                }
                                for (String field :
                                        Set.of(
                                                "readOnly",
                                                "timeoutSeconds",
                                                "idempotent",
                                                "concurrencySafe",
                                                "supportsCancellation",
                                                "approvalPolicy",
                                                "groupId",
                                                "groupDescription",
                                                "groupActiveByDefault",
                                                "activateOnSkill",
                                                "group")) {
                                    if (entry.has(field)) tool.set(field, entry.get(field));
                                }
                            }
                            return tools;
                        });
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayClient处理步骤使用。
     *
     * @param path    需要读取、写入或校验的路径。
     * @param body    当前网关客户端持有的正文对象，供相应处理步骤使用。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws GatewayException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private CompletableFuture<JsonNode> post(String path, ObjectNode body, GatewayContext context) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(baseUrl.resolve(path))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        context.traceHeaders(body.path("toolCallId").asText(null)).forEach(builder::header);
        return http.sendAsync(builder.build(), BoundedBodyHandlers.utf8(8L * 1024 * 1024))
                .thenApply(
                        response -> {
                            JsonNode bodyNode;
                            try {
                                bodyNode = JSON.readTree(response.body());
                            } catch (JsonProcessingException exception) {
                                bodyNode =
                                        JSON.createObjectNode()
                                                .put(
                                                        "message",
                                                        redactText(response.body(), context));
                            }
                            boolean success =
                                    response.statusCode() >= 200 && response.statusCode() < 300;
                            if (!success) {
                                ObjectNode details =
                                        bodyNode != null && bodyNode.isObject()
                                                ? (ObjectNode) bodyNode
                                                : JSON.createObjectNode().set("response", bodyNode);
                                details.put("status", "failed")
                                        .put("httpStatus", response.statusCode());
                                if (!details.hasNonNull("errorCode")
                                        && !details.hasNonNull("error_code")) {
                                    details.put("errorCode", "gateway_http_error");
                                }
                                details.put(
                                        "gatewayMessage",
                                        "Provider request failed; check transport authorization and owner context");
                                throw new GatewayException(
                                        result(
                                                ProviderResultStatus.ERROR,
                                                redact(details, context)));
                            }
                            if (bodyNode == null || !bodyNode.isObject()) {
                                throw new GatewayException(
                                        error(
                                                "gateway_invalid_response",
                                                "Gateway response must be a JSON object"));
                            }
                            if (bodyNode.has("protocolVersion")
                                    && bodyNode.path("protocolVersion").asInt(-1)
                                    != ProviderProtocol.CURRENT_VERSION) {
                                throw new GatewayException(
                                        error(
                                                "provider_protocol_version_unsupported",
                                                "Unsupported Provider protocolVersion"));
                            }
                            return redact(bodyNode, context);
                        });
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayClient处理步骤使用。
     *
     * @param body 当前网关客户端持有的正文对象，供相应处理步骤使用。
     * @return 本次操作返回的网关结果结果。
     */
    private GatewayResult invocationResult(JsonNode body) {
        String status = body.path("status").asText("").toLowerCase(Locale.ROOT);
        return switch (status) {
            case "success" -> result(ProviderResultStatus.SUCCESS, body);
            case "approval_required" -> error(
                    "provider_protocol_violation",
                    "Provider requested approval during invoke; approvalPolicy must be declared before"
                            + " execution");
            case "denied", "rejected", "expired", "cancelled" -> result(ProviderResultStatus.DENIED, body);
            case "failed", "error" -> result(ProviderResultStatus.ERROR, body);
            default -> error(
                    "gateway_invalid_response",
                    "Gateway response has an unknown or missing status");
        };
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayClient处理步骤使用。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次操作返回的网关结果结果。
     */
    private GatewayResult failure(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        if (cause instanceof GatewayException failure) return failure.getResult();
        if (cause instanceof HttpTimeoutException)
            return error(
                    "gateway_timeout",
                    "Gateway request timed out; execution outcome may be unknown. Do not automatically"
                            + " retry");
        return error(
                "gateway_request_failed",
                "Gateway request could not complete; check connectivity and configuration. Do not"
                        + " automatically retry");
    }

    /**
     * 判断允许。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private boolean isAllowed(String name) {
        return allowedTools.isEmpty() || allowedTools.contains(name);
    }

    /**
     * 判断是否存在受保护字段。
     *
     * @param node 当前网关客户端持有的节点对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean hasProtectedField(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (PROTECTED_FIELDS.contains(normalize(field.getKey()))
                        || hasProtectedField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (hasProtectedField(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 脱敏网关客户端。
     *
     * @param node    当前网关客户端持有的节点对象，供相应处理步骤使用。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的JSON节点结果。
     */
    private JsonNode redact(JsonNode node, GatewayContext context) {
        if (node.isTextual()) {
            return JSON.getNodeFactory().textNode(redactText(node.asText(), context));
        }
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            node.fields()
                    .forEachRemaining(
                            field ->
                                    result.set(
                                            field.getKey(),
                                            (SECRET_FIELDS.contains(normalize(field.getKey()))
                                                    || Set.of(
                                                            "ownerkey",
                                                            "sessionid",
                                                            "turnid",
                                                            "callerattributes")
                                                    .contains(
                                                            normalize(
                                                                    field
                                                                            .getKey())))
                                                    ? JSON.getNodeFactory().textNode("[redacted]")
                                                    : redact(field.getValue(), context)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(value -> result.add(redact(value, context)));
            return result;
        }
        return node;
    }

    /**
     * 脱敏文本。
     *
     * @param value   待校验、转换或保存的原始值。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次处理生成或读取的文本。
     */
    private String redactText(String value, GatewayContext context) {
        if ((!context.getOwnerKey().isEmpty() && value.equals(context.getOwnerKey()))
                || (!context.getSessionId().isEmpty() && value.equals(context.getSessionId()))
                || (!context.getTurnId().isEmpty() && value.equals(context.getTurnId()))) {
            return "[redacted]";
        }
        return value.replace(token, "[redacted]")
                .replaceAll("(?i)Bearer\\s+[^\\s\\\"'<>]+", "Bearer [redacted]");
    }

    /**
     * 规范化网关客户端。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private static String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayClient处理步骤使用。
     *
     * @return 本次操作返回的网关结果结果。
     */
    static GatewayResult missingContext() {
        return error(
                "gateway_context_missing",
                "The host must provide ownerKey, sessionId and turnId before Provider tools can execute");
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayClient处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的对象节点结果。
     */
    private static ObjectNode contextBody(GatewayContext context) {
        ObjectNode body =
                JSON.createObjectNode()
                        .put("scope", context.isRegistration() ? "registration" : "turn");
        body.put("protocolVersion", ProviderProtocol.CURRENT_VERSION);
        if (!context.getOwnerKey().isEmpty()) body.put("ownerKey", context.getOwnerKey());
        if (!context.getSessionId().isEmpty()) body.put("sessionId", context.getSessionId());
        if (!context.getTurnId().isEmpty()) body.put("turnId", context.getTurnId());
        if (!context.getCallerAttributes().isEmpty())
            body.set("callerAttributes", JSON.valueToTree(context.getCallerAttributes()));
        return body;
    }

    /**
     * 计算或取得本方法声明的结果，供当前GatewayClient处理步骤使用。
     *
     * @param code    当前网关客户端使用的代码，供其处理与状态记录使用。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @return 本次操作返回的网关结果结果。
     */
    static GatewayResult error(String code, String message) {
        return result(
                ProviderResultStatus.ERROR,
                JSON.createObjectNode()
                        .put("status", "failed")
                        .put("errorCode", code)
                        .put("errorMessage", message));
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param body   当前网关客户端持有的正文对象，供相应处理步骤使用。
     * @return 本次操作返回的网关结果结果。
     */
    private static GatewayResult result(ProviderResultStatus status, JsonNode body) {
        return new GatewayResult(
                status, JSON.convertValue(body, new TypeReference<Map<String, Object>>() {
        }));
    }
}
