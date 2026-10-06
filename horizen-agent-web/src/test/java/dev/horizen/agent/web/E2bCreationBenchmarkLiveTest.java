package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClient;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClientOptions;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import okhttp3.OkHttpClient;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 真实环境沙箱创建测速；默认跳过，仅在显式提供 E2B 配置时运行。
 */
@Tag("live-sandbox")
class E2bCreationBenchmarkLiveTest {

    @Test
    void measuresCreateToFirstCommandAndDestroyLatency() throws Exception {
        String apiKey = System.getenv("AGENT_E2B_API_KEY");
        String templateId = System.getenv("AGENT_E2B_TEMPLATE_ID");
        String apiBaseUrl = System.getenv("AGENT_E2B_API_BASE_URL");
        String runtimePattern = System.getenv("AGENT_E2B_RUNTIME_BASE_URL_PATTERN");
        Assumptions.assumeTrue(
                hasText(apiKey)
                        && hasText(templateId)
                        && hasText(apiBaseUrl)
                        && hasText(runtimePattern));

        int samples = Integer.getInteger("horizen.e2b.benchmark.samples", 10);
        HttpE2bSandboxClientOptions options =
                options(apiKey, templateId, apiBaseUrl, runtimePattern);
        HttpE2bSandboxClient client = new HttpE2bSandboxClient(options, null);
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("creation-benchmark")
                        .sessionId("creation-benchmark")
                        .build();
        List<Sample> results = new ArrayList<>();

        for (int index = 0; index < samples; index++) {
            long began = System.nanoTime();
            Sandbox sandbox = client.create(new WorkspaceSpec(), new NoopSnapshotSpec(), options);
            long created = System.nanoTime();
            boolean destroyedSuccessfully = false;
            try {
                sandbox.start();
                long started = System.nanoTime();
                var command = sandbox.exec(context, "printf sandbox-ready", 30);
                long ready = System.nanoTime();
                assertEquals("sandbox-ready", command.stdout());
                sandbox.stop();
                long stopped = System.nanoTime();
                sandbox.shutdown();
                long destroyed = System.nanoTime();
                destroyedSuccessfully = true;
                results.add(
                        new Sample(
                                millis(began, created),
                                millis(created, started),
                                millis(began, ready),
                                millis(ready, stopped),
                                millis(stopped, destroyed)));
            } finally {
                if (!destroyedSuccessfully) {
                    try {
                        sandbox.shutdown();
                    } catch (Exception ignored) {
                        // 测速失败时仍尽力释放，原始异常由测试框架报告。
                    }
                }
            }
        }

        System.out.println(
                "E2B benchmark samples="
                        + samples
                        + " clientCreateMs="
                        + summary(results.stream().map(Sample::clientCreateMs).toList())
                        + " createToReadyMs="
                        + summary(results.stream().map(Sample::createToReadyMs).toList())
                        + " remoteStartMs="
                        + summary(results.stream().map(Sample::remoteStartMs).toList())
                        + " stopMs="
                        + summary(results.stream().map(Sample::stopMs).toList())
                        + " destroyMs="
                        + summary(results.stream().map(Sample::destroyMs).toList()));
    }

    private static HttpE2bSandboxClientOptions options(
            String apiKey, String templateId, String apiBaseUrl, String runtimePattern) {
        HttpE2bSandboxClientOptions options = new HttpE2bSandboxClientOptions();
        options.setApiKey(apiKey);
        options.setTemplateId(templateId);
        options.setApiBaseUrl(apiBaseUrl);
        options.setRuntimeBaseUrlPattern(runtimePattern);
        options.setWorkspaceRoot("/tmp/horizen-agent");
        options.setSandboxTimeoutSeconds(300);
        options.setConnectTimeoutSeconds(30);
        options.setReadTimeoutSeconds(120);
        options.setHttpClient(
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS)
                        .build());
        return options;
    }

    private static String summary(List<Long> values) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return "{p50="
                + percentile(sorted, 0.50)
                + ",p95="
                + percentile(sorted, 0.95)
                + ",max="
                + sorted.get(sorted.size() - 1)
                + "}";
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index =
                Math.max(
                        0,
                        Math.min(
                                sorted.size() - 1,
                                (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private static long millis(long start, long end) {
        return Duration.ofNanos(end - start).toMillis();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Sample {
        private long clientCreateMs;
        private long remoteStartMs;
        private long createToReadyMs;
        private long stopMs;
        private long destroyMs;

        public long clientCreateMs() {
            return clientCreateMs;
        }

        public long remoteStartMs() {
            return remoteStartMs;
        }

        public long createToReadyMs() {
            return createToReadyMs;
        }

        public long stopMs() {
            return stopMs;
        }

        public long destroyMs() {
            return destroyMs;
        }
    }
}
