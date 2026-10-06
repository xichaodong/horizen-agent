package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClient;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClientOptions;
import dev.horizen.agent.tools.files.SandboxPatchTool;
import dev.horizen.agent.tools.files.SandboxSearchFilesTool;
import dev.horizen.agent.tools.process.SandboxProcessTool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import okhttp3.OkHttpClient;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 仅在显式提供 E2B 凭据时运行的真实沙箱兼容性测试。
 */
@Tag("live-sandbox")
@EnabledIfSystemProperty(named = "horizen.e2b.browser.live", matches = "true")
class E2bLiveSmokeTest {
    @Test
    void executesPythonAndStartsTheNextTurnWithACleanWorkspace() throws Exception {
        String apiKey = LiveConfiguration.environment("AGENT_E2B_API_KEY");
        String templateId = LiveConfiguration.environment("AGENT_E2B_TEMPLATE_ID");
        String apiBaseUrl = LiveConfiguration.environment("AGENT_E2B_API_BASE_URL");
        String runtimeBaseUrlPattern =
                LiveConfiguration.environment("AGENT_E2B_RUNTIME_BASE_URL_PATTERN");
        Assumptions.assumeTrue(
                apiKey != null
                        && !apiKey.isBlank()
                        && templateId != null
                        && !templateId.isBlank()
                        && apiBaseUrl != null
                        && !apiBaseUrl.isBlank()
                        && runtimeBaseUrlPattern != null
                        && !runtimeBaseUrlPattern.isBlank());

        HttpE2bSandboxClientOptions options = new HttpE2bSandboxClientOptions();
        options.setApiKey(apiKey);
        options.setApiBaseUrl(apiBaseUrl.trim());
        options.setRuntimeBaseUrlPattern(runtimeBaseUrlPattern.trim());
        options.setTemplateId(templateId);
        String workspaceRoot = environmentOr("AGENT_E2B_WORKSPACE_ROOT", "/tmp/horizen-agent");
        options.setWorkspaceRoot(workspaceRoot);
        options.setSandboxTimeoutSeconds(300);
        options.setConnectTimeoutSeconds(30);
        options.setReadTimeoutSeconds(180);
        options.setHttpClient(
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS)
                        .build());

        HttpE2bSandboxClient client = new HttpE2bSandboxClient(options, null);
        NoopSnapshotSpec snapshots = new NoopSnapshotSpec();
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("live-smoke-owner")
                        .sessionId("live-smoke-session")
                        .build();

        Sandbox first = client.create(new WorkspaceSpec(), snapshots, options);
        try {
            first.start();
            assertEquals(
                    "python-ok",
                    first.exec(context, "python3 -c \"print('python-ok')\"", 30).stdout().trim());
            first.exec(
                    context,
                    "printf 'heading\\nold\\n' > " + workspaceRoot + "/patch-live.txt",
                    30);
            SandboxBackedFilesystem sandboxFilesystem = new SandboxBackedFilesystem();
            sandboxFilesystem.setSandbox(first);
            RuntimeContext patchContext =
                    RuntimeContext.builder()
                            .userId("live-smoke-owner")
                            .sessionId("live-smoke-session")
                            .put(AbstractFilesystem.class, sandboxFilesystem)
                            .put(
                                    SandboxAcquireResult.class,
                                    SandboxAcquireResult.userManaged(first))
                            .build();
            var patchResult =
                    new SandboxPatchTool()
                            .callAsync(
                                    ToolCallParam.builder()
                                            .runtimeContext(patchContext)
                                            .input(
                                                    Map.of(
                                                            "mode",
                                                            "patch",
                                                            "patch",
                                                            """
                                                                    *** Begin Patch
                                                                    *** Update File: patch-live.txt
                                                                    @@ heading @@
                                                                     heading
                                                                    -old
                                                                    +new
                                                                    *** End Patch
                                                                    """))
                                            .build())
                            .block();
            String patchOutput = ((TextBlock) patchResult.getOutput().get(0)).getText();
            Assertions.assertFalse(patchOutput.startsWith("Error"), patchOutput);
            assertEquals(
                    "heading\nnew",
                    first.exec(context, "cat " + workspaceRoot + "/patch-live.txt", 30)
                            .stdout()
                            .trim());
            first.exec(
                    context,
                    "mkdir -p "
                            + workspaceRoot
                            + "/search-live; printf 'alpha needle omega\\n' > "
                            + workspaceRoot
                            + "/search-live/one.txt; printf 'other\\n' > "
                            + workspaceRoot
                            + "/search-live/two.log",
                    30);
            var searchResult =
                    new SandboxSearchFilesTool()
                            .callAsync(
                                    ToolCallParam.builder()
                                            .runtimeContext(patchContext)
                                            .input(
                                                    Map.of(
                                                            "pattern",
                                                            "needle",
                                                            "target",
                                                            "content",
                                                            "path",
                                                            "search-live",
                                                            "file_glob",
                                                            "*.txt"))
                                            .build())
                            .block();
            String searchOutput = ((TextBlock) searchResult.getOutput().get(0)).getText();
            Assertions.assertTrue(searchOutput.contains("one.txt"), searchOutput);
            SandboxProcessTool processTool = new SandboxProcessTool();
            ObjectMapper json = new ObjectMapper();
            var processStart =
                    processTool
                            .callAsync(
                                    ToolCallParam.builder()
                                            .runtimeContext(patchContext)
                                            .input(
                                                    Map.of(
                                                            "action",
                                                            "start",
                                                            "command",
                                                            "read line; echo process:$line"))
                                            .build())
                            .block();
            String processStartText = ((TextBlock) processStart.getOutput().get(0)).getText();
            Assertions.assertFalse(processStartText.startsWith("Error"), processStartText);
            String processId = json.readTree(processStartText).path("process_id").asText();
            processTool
                    .callAsync(
                            ToolCallParam.builder()
                                    .runtimeContext(patchContext)
                                    .input(
                                            Map.of(
                                                    "action",
                                                    "stdin",
                                                    "process_id",
                                                    processId,
                                                    "data",
                                                    "live",
                                                    "append_newline",
                                                    true))
                                    .build())
                    .block();
            var processWait =
                    processTool
                            .callAsync(
                                    ToolCallParam.builder()
                                            .runtimeContext(patchContext)
                                            .input(
                                                    Map.of(
                                                            "action",
                                                            "wait",
                                                            "process_id",
                                                            processId,
                                                            "timeout_seconds",
                                                            10))
                                            .build())
                            .block();
            assertEquals(
                    "exited",
                    json.readTree(((TextBlock) processWait.getOutput().get(0)).getText())
                            .path("status")
                            .asText());
            var processLogs =
                    processTool
                            .callAsync(
                                    ToolCallParam.builder()
                                            .runtimeContext(patchContext)
                                            .input(
                                                    Map.of(
                                                            "action",
                                                            "logs",
                                                            "process_id",
                                                            processId))
                                            .build())
                            .block();
            Assertions.assertTrue(
                    json.readTree(((TextBlock) processLogs.getOutput().get(0)).getText())
                            .path("stdout")
                            .asText()
                            .contains("process:live"));
            assertEquals(
                    "ffmpeg-ok",
                    first.exec(
                                    context,
                                    "command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null && printf"
                                            + " ffmpeg-ok",
                                    30)
                            .stdout()
                            .trim());
            first.exec(context, "printf 'persisted-ok' > " + workspaceRoot + "/live-smoke.txt", 30);
            assertEquals(
                    "persisted-ok",
                    first.exec(context, "cat " + workspaceRoot + "/live-smoke.txt", 30)
                            .stdout()
                            .trim());
        } finally {
            first.shutdown();
        }

        Sandbox clean = client.create(new WorkspaceSpec(), snapshots, options);
        try {
            clean.start();
            assertEquals(
                    "clean-workspace",
                    clean.exec(
                                    context,
                                    "test ! -e "
                                            + workspaceRoot
                                            + "/live-smoke.txt && printf clean-workspace",
                                    30)
                            .stdout()
                            .trim());
        } finally {
            clean.shutdown();
        }
    }

    private static String environmentOr(String name, String fallback) {
        String value = LiveConfiguration.environment(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
