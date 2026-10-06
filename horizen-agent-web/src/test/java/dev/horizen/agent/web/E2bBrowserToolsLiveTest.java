package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandbox;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClient;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClientOptions;
import dev.horizen.agent.tools.browser.SandboxBrowserTool;
import dev.horizen.agent.tools.process.SandboxProcessTool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * 显式启用的验收，验证 Horizen 自有工具经 envd 访问本地 CDP。
 */
@Tag("live-sandbox")
class E2bBrowserToolsLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void navigatesSnapshotsTypesAndClicks() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("horizen.e2b.browser.live"));
        HttpE2bSandboxClientOptions options = options();
        HttpE2bSandbox sandbox =
                (HttpE2bSandbox)
                        new HttpE2bSandboxClient(options, null)
                                .create(new WorkspaceSpec(), new NoopSnapshotSpec(), options);
        try {
            sandbox.start();
            assertTrue(
                    sandbox.exec(
                                    RuntimeContext.builder()
                                            .userId("browser-live-owner")
                                            .sessionId("browser-live-session")
                                            .build(),
                                    "command -v agent-browser && agent-browser --help | head -5",
                                    30)
                            .stdout()
                            .contains("agent-browser"));
            SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
            filesystem.setSandbox(sandbox);
            RuntimeContext context =
                    RuntimeContext.builder()
                            .userId("browser-live-owner")
                            .sessionId("browser-live-session")
                            .put(AbstractFilesystem.class, filesystem)
                            .put(
                                    SandboxAcquireResult.class,
                                    SandboxAcquireResult.userManaged(sandbox))
                            .build();
            List<SandboxBrowserTool> tools = SandboxBrowserTool.createAll(null);
            sandbox.exec(
                    context,
                    "mkdir -p /tmp/horizen-browser-live; printf '%s' "
                            + "'<input aria-label=\"name\"><button>go</button><h1>ready</h1>' "
                            + "> /tmp/horizen-browser-live/index.html",
                    30);
            new SandboxProcessTool()
                    .callAsync(
                            ToolCallParam.builder()
                                    .runtimeContext(context)
                                    .input(
                                            Map.of(
                                                    "action",
                                                    "start",
                                                    "command",
                                                    "cd /tmp/horizen-browser-live && python3 -m http.server 18765"))
                                    .build())
                    .block();
            Thread.sleep(500);
            call(tools.get(0), context, Map.of("url", "http://127.0.0.1:18765/index.html"));
            String snapshot = text(call(tools.get(1), context, Map.of()));
            assertTrue(snapshot.trim().startsWith("{"), snapshot);
            call(tools.get(3), context, Map.of("ref", ref(snapshot, "textbox"), "text", "horizen"));
            String clicked =
                    text(call(tools.get(2), context, Map.of("ref", ref(snapshot, "button"))));
            assertTrue(JSON.readTree(clicked).path("success").asBoolean(), clicked);
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void directEnvdAgentBrowserCommandsKeepOneCdpSession() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("horizen.e2b.browser.live"));
        HttpE2bSandbox sandbox =
                (HttpE2bSandbox)
                        new HttpE2bSandboxClient(options(), null)
                                .create(new WorkspaceSpec(), new NoopSnapshotSpec(), options());
        try {
            sandbox.start();
            var result =
                    sandbox.exec(
                            RuntimeContext.builder()
                                    .userId("browser-cli-owner")
                                    .sessionId("browser-cli-session")
                                    .build(),
                            """
                                    agent-browser --cdp http://127.0.0.1:9222 --json open 'data:text/html,<input aria-label="name"><button>Apply</button>' &&
                                    agent-browser --cdp http://127.0.0.1:9222 --json snapshot -c &&
                                    agent-browser --cdp http://127.0.0.1:9222 --json fill e1 horizen &&
                                    agent-browser --cdp http://127.0.0.1:9222 --json click e2 &&
                                    agent-browser --cdp http://127.0.0.1:9222 --json screenshot /tmp/horizen-agent/.horizen/direct-agent-browser.png
                                    """,
                            90);
            assertTrue(result.ok(), result.combinedOutput());
            assertTrue(result.stdout().contains("ref=e1"));
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void probesImagesConsoleAndDialogCommands() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("horizen.e2b.browser.live"));
        HttpE2bSandbox sandbox =
                (HttpE2bSandbox)
                        new HttpE2bSandboxClient(options(), null)
                                .create(new WorkspaceSpec(), new NoopSnapshotSpec(), options());
        try {
            sandbox.start();
            RuntimeContext context =
                    RuntimeContext.builder()
                            .userId("browser-p1-owner")
                            .sessionId("browser-p1-session")
                            .build();
            var help =
                    sandbox.exec(
                            context, "agent-browser --help | grep -i -A3 -B2 dialog || true", 30);
            assertTrue(
                    help.stdout().isBlank(), "unexpected native dialog command: " + help.stdout());
            var result =
                    sandbox.exec(
                            context,
                            """
                                    agent-browser --cdp http://127.0.0.1:9222 --json open 'data:text/html,<img alt="sample" src="https://example.com/a.png"><script>console.log("horizen-console")</script>' &&
                                    agent-browser --cdp http://127.0.0.1:9222 --json eval 'JSON.stringify([...document.images].map(i=>({src:i.src,alt:i.alt})))' &&
                                    agent-browser --cdp http://127.0.0.1:9222 --json console
                                    """,
                            90);
            assertTrue(result.ok(), result.combinedOutput());
            assertTrue(result.stdout().contains("sample"), result.stdout());
            assertTrue(result.stdout().contains("horizen-console"), result.stdout());
        } finally {
            sandbox.shutdown();
        }
    }

    private static ToolResultBlock call(
            SandboxBrowserTool tool, RuntimeContext context, Map<String, Object> input) {
        return tool.callAsync(ToolCallParam.builder().runtimeContext(context).input(input).build())
                .block();
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    private static String ref(String snapshot, String role) throws Exception {
        JsonNode refs = JSON.readTree(snapshot).path("data").path("refs");
        Iterator<Entry<String, JsonNode>> values = refs.fields();
        while (values.hasNext()) {
            var item = values.next();
            if (role.equals(item.getValue().path("role").asText())) return item.getKey();
        }
        throw new IllegalStateException("snapshot has no " + role + " ref: " + snapshot);
    }

    private static HttpE2bSandboxClientOptions options() {
        HttpE2bSandboxClientOptions value = new HttpE2bSandboxClientOptions();
        value.setApiKey(required("AGENT_E2B_API_KEY"));
        value.setApiBaseUrl(required("AGENT_E2B_API_BASE_URL"));
        value.setRuntimeBaseUrlPattern(required("AGENT_E2B_RUNTIME_BASE_URL_PATTERN"));
        value.setTemplateId(required("AGENT_E2B_TEMPLATE_ID"));
        value.setWorkspaceRoot("/tmp/horizen-agent");
        value.setSandboxTimeoutSeconds(300);
        return value;
    }

    private static String required(String name) {
        String value = LiveConfiguration.environment(name);
        Assumptions.assumeTrue(value != null && !value.isBlank());
        return value.trim();
    }
}
