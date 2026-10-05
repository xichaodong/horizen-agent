package dev.horizen.agent.tool.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.adapter.gateway.fixture.FixtureGateway;
import dev.horizen.agent.adapter.gateway.http.GatewayClient;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class FixtureGatewayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE =
            """
      {"tools":[{"name":"inspect_sample","description":"Inspect a synthetic sample",
        "inputSchema":{"type":"object","properties":{
          "sample_id":{"type":"string","enum":["sample-1"]},
          "limit":{"type":"integer","minimum":1}},
          "required":["sample_id"],"additionalProperties":false},
        "response":{"sample_id":"sample-1","count":3,"summary":"Synthetic sample"}}]}
      """;
    @TempDir Path directory;

    @Test
    void listsAndInvokesWithoutAnyContextOrCredentialsAndAlwaysMarksMockData() throws Exception {
        FixtureGateway gateway = gateway();
        ToolResultBlock list = new GatewayToolAdapter(gateway).catalogResult(null).block();
        JsonNode catalog = markedOutput(list);
        assertEquals(ToolResultState.SUCCESS, list.getState());
        assertEquals(1, catalog.path("tool_count").asInt());
        assertEquals("inspect_sample", catalog.path("tools").get(0).path("tool_name").asText());
        assertEquals("object", catalog.path("tools").get(0).at("/tool_input/type").asText());
        ToolResultBlock invoked =
                new GatewayToolAdapter(gateway)
                        .invokeGateway(
                                null, null, "inspect_sample", Map.of("sample_id", "sample-1"))
                        .block();
        assertEquals(ToolResultState.SUCCESS, invoked.getState());
        assertEquals(3, markedOutput(invoked).at("/safeResult/count").asInt());
    }

    @Test
    void validatesRequiredFieldsTypesEnumsAndUnknownTools() throws Exception {
        FixtureGateway gateway = gateway();
        for (Map<String, Object> input :
                List.<Map<String, Object>>of(
                        Map.of(), Map.of("sample_id", "sample-1", "limit", "three"),
                        Map.of("sample_id", "other-sample"),
                                Map.of("sample_id", "sample-1", "limit", 0))) {
            ToolResultBlock invalid =
                    new GatewayToolAdapter(gateway)
                            .invokeGateway(null, null, "inspect_sample", input)
                            .block();
            assertEquals(ToolResultState.ERROR, invalid.getState());
            assertEquals(
                    "schema_validation_failed", markedOutput(invalid).path("errorCode").asText());
        }
        ToolResultBlock unsupported =
                new GatewayToolAdapter(gateway)
                        .invokeGateway(null, null, "unconfigured_write", Map.of())
                        .block();
        assertEquals(ToolResultState.ERROR, unsupported.getState());
        assertEquals("unsupported_mock_tool", markedOutput(unsupported).path("errorCode").asText());
        assertEquals(
                "invalid_tool_input",
                markedOutput(
                                new GatewayToolAdapter(gateway)
                                        .invokeGateway(null, null, null, null)
                                        .block())
                        .path("errorCode")
                        .asText());
    }

    @Test
    void fixtureContentsAreAnImmutableStartupSnapshot() throws Exception {
        Path fixture = directory.resolve("snapshot.json");
        Files.writeString(fixture, FIXTURE);
        FixtureGateway gateway = new FixtureGateway(fixture);
        Files.writeString(fixture, "{\"tools\":[]}");
        assertEquals(
                1,
                markedOutput(new GatewayToolAdapter(gateway).catalogResult(null).block())
                        .path("tool_count")
                        .asInt());
        assertEquals(
                3,
                markedOutput(
                                new GatewayToolAdapter(gateway)
                                        .invokeGateway(
                                                null,
                                                null,
                                                "inspect_sample",
                                                Map.of("sample_id", "sample-1"))
                                        .block())
                        .at("/safeResult/count")
                        .asInt());
    }

    @Test
    void missingMalformedAndRemoteReferenceFixturesFailClearlyAtStartup() throws Exception {
        IllegalArgumentException missing =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new FixtureGateway(directory.resolve("missing.json")));
        assertTrue(missing.getMessage().contains("Cannot read mock gateway fixture"));
        for (String content :
                List.of(
                        "not-json",
                        "{}",
                        "{\"tools\":[{\"name\":\"missing-schema\"}]}",
                        FIXTURE.replace(
                                "\"type\":\"object\",\"properties\"",
                                "\"type\":\"object\",\"$ref\":\"https://invalid.example/schema.json\",\"properties\""))) {
            Path file = directory.resolve("invalid.json");
            Files.writeString(file, content);
            IllegalArgumentException invalid =
                    assertThrows(IllegalArgumentException.class, () -> new FixtureGateway(file));
            assertTrue(invalid.getMessage().contains("mock gateway fixture"));
        }
    }

    @Test
    void toolkitUsesTheSameModelSchemasAndMockBackendNeedsNoRuntimeContext() throws Exception {
        Toolkit mockToolkit = new Toolkit();
        GatewayTools.register(mockToolkit, gateway());
        Toolkit remoteToolkit = new Toolkit();
        GatewayTools.register(
                remoteToolkit,
                new GatewayClient(
                        URI.create("http://127.0.0.1:1/api"),
                        "synthetic-token",
                        Duration.ofSeconds(1)));
        assertEquals(
                JSON.valueToTree(remoteToolkit.getToolSchemas()),
                JSON.valueToTree(mockToolkit.getToolSchemas()));
        ToolResultBlock result =
                mockToolkit
                        .callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                toolUse("list-1", "tool_gateway_list", Map.of()))
                                        .build())
                        .block();
        assertEquals(ToolResultState.SUCCESS, result.getState(), text(result));
        markedOutput(result);
    }

    @Test
    void realAgentLoopDiscoversInvokesAndReadsMockResultWithScriptedOfflineModel()
            throws Exception {
        Toolkit toolkit = new Toolkit();
        GatewayTools.register(toolkit, gateway());
        ScriptedModel model = new ScriptedModel();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("fixture-test")
                        .sysPrompt(
                                "Use the fixture gateway. Explicitly label the response as synthetic.")
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(4)
                        .maxRetries(1)
                        .build();
        Msg result =
                agent.call(new UserMessage("Inspect sample-1 using the available tools."))
                        .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertEquals("Mock data: sample-1 has 3 synthetic items.", result.getTextContent());
        assertEquals(3, model.calls.get());
    }

    private FixtureGateway gateway() throws Exception {
        Path path = directory.resolve("fixture.json");
        Files.writeString(path, FIXTURE);
        return new FixtureGateway(path);
    }

    private static JsonNode markedOutput(ToolResultBlock result) throws Exception {
        JsonNode output = JSON.readTree(text(result).replaceFirst("^Error: ", ""));
        assertTrue(output.path("mock").asBoolean());
        assertEquals("mock", output.path("data_source").asText());
        assertTrue(output.path("notice").asText().contains("模拟数据"));
        return output;
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    private static ToolUseBlock toolUse(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(input)
                .content(JSON.valueToTree(input).toString())
                .build();
    }

    private static final class ScriptedModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "offline-fixture-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        int call = calls.getAndIncrement();
                        ContentBlock output;
                        if (call == 0) {
                            output = toolUse("list-1", "tool_gateway_list", Map.of());
                        } else {
                            ToolResultBlock latest =
                                    messages.stream()
                                            .flatMap(
                                                    message ->
                                                            message
                                                                    .getContentBlocks(
                                                                            ToolResultBlock.class)
                                                                    .stream())
                                            .reduce((first, second) -> second)
                                            .orElseThrow();
                            JsonNode body;
                            try {
                                body = markedOutput(latest);
                            } catch (Exception exception) {
                                return Flux.error(exception);
                            }
                            assertEquals(ToolResultState.SUCCESS, latest.getState());
                            if (call == 1) {
                                assertEquals(
                                        "inspect_sample", body.at("/tools/0/tool_name").asText());
                                output =
                                        toolUse(
                                                "invoke-1",
                                                "tool_gateway_invoke",
                                                Map.of(
                                                        "tool_name",
                                                        "inspect_sample",
                                                        "tool_input",
                                                        Map.of("sample_id", "sample-1")));
                            } else {
                                assertEquals(3, body.at("/safeResult/count").asInt());
                                output =
                                        TextBlock.builder()
                                                .text("Mock data: sample-1 has 3 synthetic items.")
                                                .build();
                            }
                        }
                        return Flux.just(ChatResponse.builder().content(List.of(output)).build());
                    });
        }
    }
}
