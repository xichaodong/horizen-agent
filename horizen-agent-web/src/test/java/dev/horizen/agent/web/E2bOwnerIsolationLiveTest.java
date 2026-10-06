package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bFilesystemSpec;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 两个 owner 共用同名 session 时的真实沙箱隔离测试。
 */
@Tag("live-sandbox")
@EnabledIfSystemProperty(named = "horizen.e2b.browser.live", matches = "true")
class E2bOwnerIsolationLiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ownersWithSameSessionIdCannotSeeEachOthersFiles() throws Exception {
        String apiKey = env("AGENT_E2B_API_KEY");
        String apiBaseUrl = env("AGENT_E2B_API_BASE_URL");
        String runtimePattern = env("AGENT_E2B_RUNTIME_BASE_URL_PATTERN");
        String templateId = env("AGENT_E2B_TEMPLATE_ID");
        Assumptions.assumeTrue(
                apiKey != null
                        && apiBaseUrl != null
                        && runtimePattern != null
                        && templateId != null);

        HttpE2bFilesystemSpec sandbox =
                new HttpE2bFilesystemSpec()
                        .apiKey(apiKey)
                        .apiBaseUrl(apiBaseUrl)
                        .runtimeBaseUrlPattern(runtimePattern)
                        .templateId(templateId)
                        .workspaceRoot(envOr("AGENT_E2B_WORKSPACE_ROOT", "/tmp/horizen-agent"))
                        .snapshotSpec(new NoopSnapshotSpec());
        sandbox.isolationScope(IsolationScope.USER);

        Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(
                temporaryDirectory.resolve("workspace/AGENTS.md"),
                "Run the deterministic owner isolation test.");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("e2b-owner-isolation-live-test")
                        .sysPrompt("Write and read the requested marker deterministically.")
                        .model(new OwnerMarkerModel())
                        .workspace(temporaryDirectory.resolve("workspace"))
                        .filesystem(sandbox)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            var replies =
                    Mono.zip(
                                    completed(runtime, request("turn-a", "owner-a", "marker-a"))
                                            .subscribeOn(Schedulers.parallel()),
                                    completed(runtime, request("turn-b", "owner-b", "marker-b"))
                                            .subscribeOn(Schedulers.parallel()))
                            .block(Duration.ofMinutes(3));

            assertEquals("marker-a", replies.getT1().getText());
            assertEquals("marker-b", replies.getT2().getText());
        }
    }

    private static Mono<AgentRuntimeEvent> completed(
            HarnessAgentRuntime runtime, AgentTurnRequest request) {
        return runtime.stream(request)
                .filter(event -> event.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED)
                .single();
    }

    private static AgentTurnRequest request(String turnId, String owner, String marker) {
        return AgentTurnRequest.builder()
                .turnId(turnId)
                .ownerKey(owner)
                .sessionId("same-session")
                .message(marker)
                .build();
    }

    private static String env(String name) {
        String value = LiveConfiguration.environment(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String envOr(String name, String fallback) {
        String value = env(name);
        return value == null ? fallback : value;
    }

    private static final class OwnerMarkerModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "owner-isolation-live-scripted-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            List<String> results = toolResults(messages);
            String marker = latestUserMessage(messages);
            if (results.isEmpty()) {
                return tool(
                        "write-marker",
                        "write_file",
                        Map.of("path", "shared/marker.txt", "content", marker));
            }
            if (results.size() == 1) {
                return tool(
                        "read-marker",
                        "execute",
                        Map.of("command", "sleep 5; cat shared/marker.txt", "timeout", 15));
            }
            String output = results.get(results.size() - 1);
            if (output.contains("marker-a")) {
                return text("marker-a");
            }
            if (output.contains("marker-b")) {
                return text("marker-b");
            }
            return text("missing-marker");
        }

        private static String latestUserMessage(List<Msg> messages) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                Msg message = messages.get(index);
                if (message.getRole() == MsgRole.USER) {
                    return message.getTextContent();
                }
            }
            return "missing";
        }

        private static List<String> toolResults(List<Msg> messages) {
            List<String> result = new ArrayList<>();
            messages.stream()
                    .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .forEach(result::add);
            return result;
        }

        private static Flux<ChatResponse> tool(String id, String name, Map<String, Object> input) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id(id)
                                                    .name(name)
                                                    .input(input)
                                                    .content(toJson(input))
                                                    .build()))
                            .build());
        }

        private static Flux<ChatResponse> text(String value) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text(value).build()))
                            .build());
        }

        private static String toJson(Map<String, Object> input) {
            try {
                return new ObjectMapper().writeValueAsString(input);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
