package dev.horizen.agent.tool.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.horizen.agent.adapter.gateway.http.GatewayClient;
import dev.horizen.agent.observability.ExecutionTraceContext;
import dev.horizen.agent.provider.spi.gateway.GatewayCallerAttributes;
import dev.horizen.agent.provider.spi.gateway.GatewayContext;
import dev.horizen.agent.tool.adapter.ToolAdapterContext;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

class GatewayClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "test-gateway-token";
    private static final GatewayContext REGISTRATION = new GatewayContext("", "", "", true);
    private static final GatewayContext CONTEXT =
            new GatewayContext("owner-1", "session-1", "turn-1", false);
    private static final String CATALOG =
            """
                    {"tools":[
                      {"name":"inspect","description":"Inspect an item","inputSchema":{"type":"object"},
                       "riskLevel":"low","requiresApproval":false},
                      {"name":"report","description":"Report","input_schema":"{\\\"type\\\":\\\"object\\\"}"}
                    ]}
                    """;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private URI baseUrl;
    private volatile String catalogResponse;
    private volatile String invokeResponse;
    private volatile int catalogStatus;
    private volatile int invokeStatus;
    private final Map<String, String> traceHeaders = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        catalogResponse = CATALOG;
        invokeResponse = "{\"status\":\"success\",\"safeResult\":{\"count\":3}}";
        catalogStatus = 200;
        invokeStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/tools/list", exchange -> respond(exchange, catalogStatus, catalogResponse));
        server.createContext(
                "/api/tools/invoke", exchange -> respond(exchange, invokeStatus, invokeResponse));
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void normalizesCatalogAndKeepsConfigurationOutOfModelSchema() throws Exception {
        GatewayClient client = client();
        ToolResultBlock result = new GatewayToolAdapter(client).catalogResult(REGISTRATION).block();
        assertEquals(ToolResultState.SUCCESS, result.getState(), text(result));
        JsonNode output = output(result);
        assertEquals("success", output.path("status").asText());
        assertEquals(2, output.path("tool_count").asInt());
        assertEquals("inspect", output.path("tools").get(0).path("tool_name").asText());
        assertEquals(
                "object", output.path("tools").get(1).path("tool_input").path("type").asText());
        assertEquals("Bearer " + TOKEN, requests.get(0).authorization());
        assertEquals(
                JSON.readTree("{\"scope\":\"registration\",\"protocolVersion\":1}"),
                requests.get(0).body());
        Toolkit toolkit = new Toolkit();
        GatewayTools.register(toolkit, client);
        String schemas = JSON.writeValueAsString(toolkit.getToolSchemas());
        assertTrue(schemas.contains("tool_gateway_list"));
        assertTrue(schemas.contains("tool_gateway_invoke"));
        assertFalse(schemas.contains(TOKEN));
        assertFalse(schemas.contains("sessionId"));
        assertFalse(client.toString().contains(TOKEN));
        assertFalse(CONTEXT.toString().contains(CONTEXT.getOwnerKey()));
    }

    @Test
    void hostContextAndToolCallMetadataSupplyInvocationIdentity() throws Exception {
        Toolkit toolkit = new Toolkit();
        GatewayTools.register(toolkit, client());
        RuntimeContext runtime =
                RuntimeContext.builder()
                        .userId("owner-1")
                        .sessionId("session-1")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner-1", "session-1", "turn-1"))
                        .build();
        Map<String, Object> arguments =
                Map.of("tool_name", "inspect", "tool_input", Map.of("item_id", "item-1"));
        ToolResultBlock result = call(toolkit, "tool_gateway_invoke", arguments, runtime);
        assertEquals(ToolResultState.SUCCESS, result.getState(), text(result));
        assertEquals(3, output(result).path("safeResult").path("count").asInt());
        assertEquals(2, requests.size());
        assertEquals("/api/tools/list", requests.get(0).path());
        JsonNode body = requests.get(1).body();
        assertEquals(CONTEXT.getSessionId(), body.path("sessionId").asText());
        assertEquals(CONTEXT.getOwnerKey(), body.path("ownerKey").asText());
        assertEquals(CONTEXT.getTurnId(), body.path("turnId").asText());
        assertEquals("turn", body.path("scope").asText());
        assertEquals("call-1", body.path("toolCallId").asText());
        assertEquals("inspect", body.path("toolName").asText());
        assertEquals("item-1", body.path("input").path("item_id").asText());
        assertEquals("Bearer " + TOKEN, requests.get(1).authorization());
    }

    @Test
    void rejectsMissingContextAndIdentitySpoofingBeforeNetwork() {
        Toolkit toolkit = new Toolkit();
        GatewayTools.register(toolkit, client());
        RuntimeContext runtime = RuntimeContext.builder().sessionId("untrusted-local-id").build();
        ToolResultBlock missing =
                call(
                        toolkit,
                        "tool_gateway_invoke",
                        Map.of("tool_name", "inspect", "tool_input", Map.of()),
                        runtime);
        assertEquals(ToolResultState.ERROR, missing.getState());
        assertTrue(text(missing).contains("gateway_context_missing"), text(missing));
        ToolResultBlock identityArgument =
                call(toolkit, "tool_gateway_list", Map.of("sessionId", "injected"), runtime);
        assertEquals(ToolResultState.ERROR, identityArgument.getState());
        ToolResultBlock nestedIdentity =
                new GatewayToolAdapter(client())
                        .invokeGateway(
                                CONTEXT,
                                "call-1",
                                "inspect",
                                Map.of("nested", List.of(Map.of("merchant_id", "injected"))))
                        .block();
        assertTrue(text(nestedIdentity).contains("protected_tool_input"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void sendsBusinessAttributesOnlyFromHostContext() throws Exception {
        Map<String, String> principal =
                Map.of(
                        "tenantId",
                        "test",
                        "businessSubjectType",
                        "shop",
                        "businessSubjectId",
                        "42",
                        "userId",
                        "100",
                        "optId",
                        "101");
        RuntimeContext runtime =
                RuntimeContext.builder()
                        .userId("owner-1")
                        .sessionId("session-1")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner-1", "session-1", "turn-1"))
                        .put(GatewayCallerAttributes.class, new GatewayCallerAttributes(principal))
                        .build();
        GatewayContext context =
                GatewayToolAdapter.gatewayContext(ToolAdapterContext.from(runtime));
        var result =
                new GatewayToolAdapter(client())
                        .invokeGateway(
                                context, "call-business", "inspect", Map.of("item_id", "item-1"))
                        .block();
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals(JSON.valueToTree(principal), requests.get(1).body().get("callerAttributes"));
        assertFalse(requests.get(1).body().path("input").has("callerAttributes"));
        int count = requests.size();
        result =
                new GatewayToolAdapter(client())
                        .invokeGateway(
                                context,
                                "call-spoof",
                                "inspect",
                                Map.of("callerAttributes", principal))
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(count, requests.size());
        assertFalse(context.toString().contains("100"));
    }

    @Test
    void filtersAllowedToolsAndChecksFreshCatalogBeforeInvoke() throws Exception {
        GatewayClient client =
                new GatewayClient(baseUrl, TOKEN, Duration.ofSeconds(2), Set.of("inspect"));
        JsonNode tools = output(new GatewayToolAdapter(client).catalogResult(REGISTRATION).block());
        assertEquals(1, tools.path("tool_count").asInt());
        requests.clear();
        assertTrue(
                text(new GatewayToolAdapter(client)
                        .invokeGateway(CONTEXT, "call-1", "report", Map.of())
                        .block())
                        .contains("tool_not_allowed"));
        assertTrue(requests.isEmpty());
        catalogResponse = "{\"tools\":[]}";
        assertTrue(
                text(new GatewayToolAdapter(client)
                        .invokeGateway(CONTEXT, "call-1", "inspect", Map.of())
                        .block())
                        .contains("tool_not_available"));
        assertEquals(1, requests.size());
        assertEquals("/api/tools/list", requests.get(0).path());
    }

    @Test
    void preservesProviderFailureAndRejectsLateApprovalProtocol() throws Exception {
        invokeResponse =
                "{\"status\":\"failed\",\"errorCode\":\"run_not_running\",\"errorMessage\":\"run is"
                        + " inactive\"}";
        ToolResultBlock failed =
                new GatewayToolAdapter(client())
                        .invokeGateway(CONTEXT, "call-1", "inspect", Map.of())
                        .block();
        assertEquals(ToolResultState.ERROR, failed.getState());
        assertEquals("run_not_running", output(failed).path("errorCode").asText());
        invokeResponse =
                "{\"protocolVersion\":1,\"status\":\"denied\",\"errorCode\":\"not_authorized\"}";
        ToolResultBlock denied =
                new GatewayToolAdapter(client())
                        .invokeGateway(CONTEXT, "call-denied", "inspect", Map.of())
                        .block();
        assertEquals(ToolResultState.DENIED, denied.getState());
        assertEquals("not_authorized", output(denied).path("errorCode").asText());
        invokeResponse =
                "{\"status\":\"approval_required\",\"message\":\"not"
                        + " executed\",\"safeResult\":{\"approvalId\":\"approval-1\"}}";
        ToolResultBlock approval =
                new GatewayToolAdapter(client())
                        .invokeGateway(CONTEXT, "call-2", "inspect", Map.of())
                        .block();
        assertEquals(ToolResultState.ERROR, approval.getState());
        assertEquals("provider_protocol_violation", output(approval).path("errorCode").asText());
    }

    @Test
    void preservesHttpErrorCodeRedactsSensitiveTextAndDoesNotRetry() throws Exception {
        invokeStatus = 503;
        invokeResponse =
                JSON.writeValueAsString(
                        Map.of(
                                "errorCode",
                                "upstream_down",
                                "errorMessage",
                                "Failed with " + TOKEN,
                                "ownerKey",
                                CONTEXT.getOwnerKey(),
                                "sessionId",
                                CONTEXT.getSessionId(),
                                "authorization",
                                "other-secret"));
        ToolResultBlock result =
                new GatewayToolAdapter(client())
                        .invokeGateway(CONTEXT, "call-1", "inspect", Map.of())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        JsonNode output = output(result);
        assertEquals("upstream_down", output.path("errorCode").asText());
        assertEquals(503, output.path("httpStatus").asInt());
        assertFalse(text(result).contains(TOKEN));
        assertFalse(text(result).contains(CONTEXT.getSessionId()));
        assertFalse(text(result).contains(CONTEXT.getOwnerKey()));
        assertFalse(text(result).contains("other-secret"));
        assertEquals(
                1, requests.stream().filter(request -> request.path().endsWith("invoke")).count());
    }

    @Test
    void doesNotFollowRedirectsOrInvokeAfterCatalogFailure() {
        server.removeContext("/api/tools/list");
        server.createContext(
                "/api/tools/list",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", baseUrl + "/tools/invoke");
                    respond(exchange, 307, "{\"errorCode\":\"redirected\"}");
                });
        ToolResultBlock result =
                new GatewayToolAdapter(client())
                        .invokeGateway(CONTEXT, "call-1", "inspect", Map.of())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text(result).contains("redirected"));
        assertEquals(1, requests.size());
    }

    @Test
    void rejectsMalformedCatalogWithoutInvokingBusinessTool() {
        catalogResponse = "{\"tools\":[{\"name\":\"inspect\",\"inputSchema\":\"invalid-json\"}]}";
        ToolResultBlock result =
                new GatewayToolAdapter(client())
                        .invokeGateway(CONTEXT, "call-1", "inspect", Map.of())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text(result).contains("gateway_invalid_catalog"));
        assertEquals(1, requests.size());
    }

    @Test
    void rejectsUnsupportedProviderProtocolVersion() {
        catalogResponse = "{\"protocolVersion\":2,\"tools\":[]}";
        ToolResultBlock result =
                new GatewayToolAdapter(client()).catalogResult(REGISTRATION).block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text(result).contains("provider_protocol_version_unsupported"), text(result));
    }

    @Test
    void propagatesTrustedInvocationAndToolSpanHeadersAndRejectsForgedCoordinates() {
        var correlation =
                new ExecutionTraceContext(
                        "trace-1", "invocation-1", "turn-1", "session-1", "session-1", "root-span");
        correlation.registerTool("call-1", "tool-span");
        var runtime =
                RuntimeContext.builder()
                        .userId("owner-1")
                        .sessionId("session-1")
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner-1", "session-1", "turn-1"))
                        .put(ExecutionTraceContext.class, correlation)
                        .build();
        var context = GatewayToolAdapter.gatewayContext(ToolAdapterContext.from(runtime));
        assertEquals(
                ToolResultState.SUCCESS,
                new GatewayToolAdapter(client())
                        .invokeGateway(context, "call-1", "inspect", Map.of("item_id", "1"))
                        .block()
                        .getState());
        assertEquals(
                Map.of(
                        "X-Horizen-Trace-Id",
                        "trace-1",
                        "X-Horizen-Span-Id",
                        "tool-span",
                        "X-Horizen-Turn-Id",
                        "turn-1",
                        "X-Horizen-Invocation-Id",
                        "invocation-1"),
                traceHeaders);
        int count = requests.size();
        assertEquals(
                ToolResultState.ERROR,
                new GatewayToolAdapter(client())
                        .invokeGateway(context, "call-2", "inspect", Map.of("traceId", "forged"))
                        .block()
                        .getState());
        assertEquals(count, requests.size());
    }

    private GatewayClient client() {
        return new GatewayClient(baseUrl, TOKEN, Duration.ofSeconds(2));
    }

    private void respond(HttpExchange exchange, int status, String response) throws IOException {
        if (exchange.getRequestURI().getPath().endsWith("invoke")) {
            for (String header :
                    List.of(
                            "X-Horizen-Trace-Id",
                            "X-Horizen-Span-Id",
                            "X-Horizen-Turn-Id",
                            "X-Horizen-Invocation-Id")) {
                String value = exchange.getRequestHeaders().getFirst(header);
                if (value != null) traceHeaders.put(header, value);
            }
        }
        requests.add(
                new Request(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        JSON.readTree(exchange.getRequestBody())));
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static ToolResultBlock call(
            Toolkit toolkit, String name, Map<String, Object> input, RuntimeContext context) {
        return toolkit.callTool(
                        ToolCallParam.builder()
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name(name)
                                                .input(input)
                                                .content(JSON.valueToTree(input).toString())
                                                .build())
                                .input(input)
                                .runtimeContext(context)
                                .build())
                .block(Duration.ofSeconds(5));
    }

    private static JsonNode output(ToolResultBlock result) throws Exception {
        return JSON.readTree(text(result).replaceFirst("^Error: ", ""));
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Request {
        private String path;
        private String authorization;
        private JsonNode body;

        public String path() {
            return path;
        }

        public String authorization() {
            return authorization;
        }

        public JsonNode body() {
            return body;
        }
    }
}
