package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.adapter.agentscope.artifact.OwnerScopedLocalArtifactTarget;
import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bFilesystemSpec;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import okhttp3.OkHttpClient;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 用真实沙箱验证 Skill 脚本、Shell 工具和产物交付组成的完整 Agent Loop。 */
@Tag("live-sandbox")
class E2bAgentSkillLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temporaryDirectory;

    @Test
    void loadsSkillRunsProjectedScriptAndDeliversArtifact() throws Exception {
        LiveConfiguration live = LiveConfiguration.fromEnvironment();
        Assumptions.assumeTrue(live != null);

        Path workspace = temporaryDirectory.resolve("workspace");
        copyTree(exampleWorkspace(), workspace);
        Path artifacts = temporaryDirectory.resolve("artifacts");
        OwnerScopedLocalArtifactTarget artifactTarget =
                new OwnerScopedLocalArtifactTarget(artifacts, 1024 * 1024);

        HttpE2bFilesystemSpec sandbox =
                new HttpE2bFilesystemSpec()
                        .apiKey(live.apiKey())
                        .apiBaseUrl(live.apiBaseUrl())
                        .runtimeBaseUrlPattern(live.runtimeBaseUrlPattern())
                        .templateId(live.templateId())
                        .workspaceRoot(live.workspaceRoot())
                        .httpClient(
                                new OkHttpClient.Builder()
                                        .connectTimeout(30, TimeUnit.SECONDS)
                                        .readTimeout(180, TimeUnit.SECONDS)
                                        .build())
                        .snapshotSpec(new NoopSnapshotSpec());
        sandbox.isolationScope(IsolationScope.USER);

        ArtifactSkillModel model = new ArtifactSkillModel();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("e2b-artifact-live-test")
                        .sysPrompt(
                                "Load the matching skill, run its existing script, then deliver the artifact.")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(sandbox)
                        .artifactDeliveryTarget(artifactTarget)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .build();

        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            AgentRuntimeEvent completed =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .turnId("artifact-live-turn")
                                            .ownerKey("owner-a")
                                            .sessionId("same-session")
                                            .message(
                                                    "Generate and deliver the sample artifact report.")
                                            .build())
                            .filter(
                                    event ->
                                            event.getType()
                                                    == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .single()
                            .block(Duration.ofMinutes(3));

            assertEquals("artifact-ready", completed.getText());
        }

        Path delivered = artifactTarget.resolve("owner-a", "same-session", "artifact-report.json");
        JsonNode report = JSON.readTree(Files.readString(delivered));
        assertEquals(3, report.path("orderCount").asInt());
        assertEquals("40.00", report.path("totalAmount").asText());
        assertEquals("13.33", report.path("averageAmount").asText());
        assertTrue(model.sawRequiredTools());
    }

    private static Path exampleWorkspace() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path direct = current.resolve("examples/e2b-artifact-workspace");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path sibling = current.resolve("../examples/e2b-artifact-workspace").normalize();
        if (Files.isDirectory(sibling)) {
            return sibling;
        }
        throw new IllegalStateException("找不到公开 E2B Artifact Skill 示例工作区");
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class LiveConfiguration {
        private String apiKey;
        private String apiBaseUrl;
        private String runtimeBaseUrlPattern;
        private String templateId;
        private String workspaceRoot;

        public String apiKey() {
            return apiKey;
        }

        public String apiBaseUrl() {
            return apiBaseUrl;
        }

        public String runtimeBaseUrlPattern() {
            return runtimeBaseUrlPattern;
        }

        public String templateId() {
            return templateId;
        }

        public String workspaceRoot() {
            return workspaceRoot;
        }

        private static LiveConfiguration fromEnvironment() {
            String apiKey = env("AGENT_E2B_API_KEY");
            String apiBaseUrl = env("AGENT_E2B_API_BASE_URL");
            String runtime = env("AGENT_E2B_RUNTIME_BASE_URL_PATTERN");
            String template = env("AGENT_E2B_TEMPLATE_ID");
            if (apiKey == null || apiBaseUrl == null || runtime == null || template == null) {
                return null;
            }
            String root = env("AGENT_E2B_WORKSPACE_ROOT");
            return new LiveConfiguration(
                    apiKey,
                    apiBaseUrl,
                    runtime,
                    template,
                    root == null ? "/tmp/horizen-agent" : root);
        }

        private static String env(String name) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    private static final class ArtifactSkillModel extends ChatModelBase {
        private static final Pattern SKILL_ID =
                Pattern.compile("<skill-id>([^<]*artifact-report[^<]*)</skill-id>");
        private static final Pattern FILES_ROOT = Pattern.compile("Files root: ([^\\r\\n]+)");
        private boolean sawLoadSkill;
        private boolean sawExecute;
        private boolean sawDeliver;

        @Override
        public String getModelName() {
            return "artifact-skill-live-scripted-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            List<String> results = toolResults(messages);
            if (results.isEmpty()) {
                sawLoadSkill = hasTool(tools, "load_skill_through_path");
                String prompt =
                        messages.stream()
                                .map(Msg::getTextContent)
                                .reduce("", (left, right) -> left + "\n" + right);
                var skill = SKILL_ID.matcher(prompt);
                if (!skill.find()) {
                    return text("missing-skill");
                }
                return tool(
                        "load-skill",
                        "load_skill_through_path",
                        Map.of("skillId", skill.group(1), "path", "SKILL.md"));
            }
            if (results.size() == 1) {
                sawExecute = hasTool(tools, "execute");
                var root = FILES_ROOT.matcher(results.get(0));
                if (!root.find()) {
                    return text("missing-files-root");
                }
                String filesRoot = root.group(1).trim();
                String command =
                        "python3 "
                                + quote(filesRoot + "/scripts/build_report.py")
                                + " --input "
                                + quote(filesRoot + "/references/orders.json")
                                + " --output outputs/artifact-report.json";
                return tool("run-script", "execute", Map.of("command", command, "timeout", 60));
            }
            if (results.size() == 2) {
                sawDeliver = hasTool(tools, "deliver_artifact");
                return tool(
                        "deliver",
                        "deliver_artifact",
                        Map.of(
                                "filePath", "outputs/artifact-report.json",
                                "fileName", "artifact-report.json",
                                "description", "Public sample order summary",
                                "force", false));
            }
            return text("artifact-ready");
        }

        private boolean sawRequiredTools() {
            return sawLoadSkill && sawExecute && sawDeliver;
        }

        private static boolean hasTool(List<ToolSchema> tools, String name) {
            return tools.stream().anyMatch(tool -> name.equals(tool.getName()));
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
            try {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                ToolUseBlock.builder()
                                                        .id(id)
                                                        .name(name)
                                                        .input(input)
                                                        .content(JSON.writeValueAsString(input))
                                                        .build()))
                                .build());
            } catch (Exception error) {
                return Flux.error(error);
            }
        }

        private static Flux<ChatResponse> text(String value) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text(value).build()))
                            .build());
        }

        private static String quote(String value) {
            return "'" + value.replace("'", "'\"'\"'") + "'";
        }
    }
}
