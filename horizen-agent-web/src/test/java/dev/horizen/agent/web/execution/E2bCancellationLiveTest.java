package dev.horizen.agent.web.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.application.turn.TurnExecutionManager;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bFilesystemSpec;
import dev.horizen.agent.web.LiveConfiguration;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 取消 Agent 调用后必须回收真实沙箱，避免长命令继续占用资源。
 */
@Tag("live-sandbox")
@EnabledIfSystemProperty(named = "horizen.e2b.browser.live", matches = "true")
class E2bCancellationLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void cancellationDeletesSandboxRunningLongCommand() throws Exception {
        String apiKey = env("AGENT_E2B_API_KEY");
        String apiBaseUrl = env("AGENT_E2B_API_BASE_URL");
        String runtimePattern = env("AGENT_E2B_RUNTIME_BASE_URL_PATTERN");
        String templateId = env("AGENT_E2B_TEMPLATE_ID");
        Assumptions.assumeTrue(
                apiKey != null
                        && apiBaseUrl != null
                        && runtimePattern != null
                        && templateId != null);

        LifecycleRecorder recorder = new LifecycleRecorder();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS)
                        .addInterceptor(recorder)
                        .build();
        HttpE2bFilesystemSpec sandbox =
                new HttpE2bFilesystemSpec()
                        .apiKey(apiKey)
                        .apiBaseUrl(apiBaseUrl)
                        .runtimeBaseUrlPattern(runtimePattern)
                        .templateId(templateId)
                        .workspaceRoot(envOr("AGENT_E2B_WORKSPACE_ROOT", "/tmp/horizen-agent"))
                        .httpClient(http)
                        .snapshotSpec(new NoopSnapshotSpec());
        sandbox.isolationScope(IsolationScope.USER);

        Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(
                temporaryDirectory.resolve("workspace/AGENTS.md"),
                "Run the deterministic cancellation test command.");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("e2b-cancellation-live-test")
                        .sysPrompt("Run the requested command.")
                        .model(new LongCommandModel())
                        .workspace(temporaryDirectory.resolve("workspace"))
                        .filesystem(sandbox)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .build();

        CountDownLatch toolStarted = new CountDownLatch(1);
        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent);
             TurnExecutionManager manager = new TurnExecutionManager(runtime)) {
            AgentTurnRequest turn =
                    AgentTurnRequest.builder()
                            .turnId("cancel-live-turn")
                            .ownerKey("cancel-owner")
                            .sessionId("cancel-session")
                            .message("run long command")
                            .build();
            Flux<AgentRuntimeEvent> events =
                    manager.start(turn, runtime.stream(turn), Duration.ofMinutes(3), null);
            Disposable browserObserver =
                    events.subscribe(
                            event -> {
                                if (event.getType() == AgentRuntimeEvent.Type.TOOL_STARTED) {
                                    toolStarted.countDown();
                                }
                            });

            assertTrue(toolStarted.await(60, TimeUnit.SECONDS), "长命令未开始");
            browserObserver.dispose();
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            assertTrue(recorder.deleted.getCount() == 1, "页面断开不应删除后台沙箱");
            assertEquals(
                    TurnExecutionManager.CancelResult.CANCELLED,
                    manager.cancel("cancel-owner", "cancel-session", "cancel-live-turn"));
            assertTrue(recorder.deleted.await(30, TimeUnit.SECONDS), "取消后沙箱未及时删除");
            assertTrue(
                    runtime.sessionExecution("cancel-owner", "cancel-session")
                            .map(state -> state.getStatus() == TurnStatus.CANCELLED)
                            .orElse(false),
                    "Session 未记录取消后的 Turn 状态");
        }

        assertNotNull(recorder.sandboxId.get());
        assertNotNull(recorder.accessToken.get());
        assertTrue(
                recorder.deleteStatus.get() >= 200 && recorder.deleteStatus.get() < 300,
                "沙箱删除接口未成功");
        assertSandboxTerminated(apiBaseUrl, apiKey, recorder.sandboxId.get());
        assertRuntimeUnavailable(
                runtimePattern, recorder.sandboxId.get(), recorder.accessToken.get());
    }

    private static void assertSandboxTerminated(String apiBaseUrl, String apiKey, String sandboxId)
            throws Exception {
        String base =
                apiBaseUrl.endsWith("/")
                        ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                        : apiBaseUrl;
        String lastState = "unknown";
        int lastStatus = -1;
        for (int attempt = 0; attempt < 30; attempt++) {
            Request request =
                    new Request.Builder()
                            .url(base + "/sandboxes/" + sandboxId)
                            .header("X-API-KEY", apiKey)
                            .get()
                            .build();
            try (Response response = new OkHttpClient().newCall(request).execute()) {
                lastStatus = response.code();
                if (lastStatus == 404 || lastStatus == 410) {
                    return;
                }
                String body = response.body() == null ? "" : response.body().string();
                if (!body.isBlank()) {
                    lastState = JSON.readTree(body).path("state").asText("unknown");
                    String normalized = lastState.toLowerCase(Locale.ROOT);
                    if (normalized.equals("paused")
                            || normalized.equals("stopped")
                            || normalized.equals("killed")
                            || normalized.equals("deleted")
                            || normalized.equals("dead")
                            || normalized.equals("terminated")) {
                        return;
                    }
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("删除后沙箱未进入终态，HTTP " + lastStatus + "，state=" + lastState);
    }

    private static void assertRuntimeUnavailable(
            String runtimePattern, String sandboxId, String accessToken) throws Exception {
        String base = runtimePattern.replace("{sandbox_id}", sandboxId);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String requestJson =
                "{\"process\":{\"cmd\":\"bash\",\"args\":[\"-l\",\"-c\",\"echo alive\"]}}";
        Request request =
                new Request.Builder()
                        .url(base + "/process.Process/StartSync")
                        .header("X-Access-Token", accessToken)
                        .post(
                                RequestBody.create(
                                        requestJson,
                                        MediaType.get("application/json; charset=utf-8")))
                        .build();
        OkHttpClient client =
                new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build();
        try (Response response = client.newCall(request).execute()) {
            assertTrue(!response.isSuccessful(), "回收后 envd 仍接受命令");
        } catch (IOException expected) {
            // 实例释放后域名或端口不可达同样表示命令执行面已停止。
        }
    }

    private static String env(String name) {
        String value = LiveConfiguration.environment(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String envOr(String name, String fallback) {
        String value = env(name);
        return value == null ? fallback : value;
    }

    private static final class LifecycleRecorder implements Interceptor {
        private final AtomicReference<String> sandboxId = new AtomicReference<>();
        private final AtomicReference<String> accessToken = new AtomicReference<>();
        private final AtomicInteger deleteStatus = new AtomicInteger(-1);
        private final CountDownLatch deleted = new CountDownLatch(1);

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = chain.proceed(request);
            String path = request.url().encodedPath();
            if ("POST".equals(request.method())
                    && "/sandboxes".equals(path)
                    && response.isSuccessful()) {
                String body = response.peekBody(64 * 1024).string();
                var created = JSON.readTree(body);
                sandboxId.compareAndSet(null, created.path("sandboxID").asText(null));
                accessToken.compareAndSet(null, created.path("envdAccessToken").asText(null));
            }
            if ("DELETE".equals(request.method()) && path.startsWith("/sandboxes/")) {
                deleteStatus.set(response.code());
                deleted.countDown();
            }
            return response;
        }
    }

    private static final class LongCommandModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "cancellation-live-scripted-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            Map<String, Object> input =
                    Map.of(
                            "command",
                            "echo started > cancellation-marker; sleep 120",
                            "timeout",
                            120);
            try {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                ToolUseBlock.builder()
                                                        .id("long-command")
                                                        .name("execute")
                                                        .input(input)
                                                        .content(JSON.writeValueAsString(input))
                                                        .build()))
                                .build());
            } catch (Exception error) {
                return Flux.error(error);
            }
        }
    }
}
