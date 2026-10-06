package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.artifact.ArtifactEventCollector;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandbox;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClient;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClientOptions;
import dev.horizen.agent.storage.bos.BosArtifactContentStore;
import dev.horizen.agent.storage.bos.BosArtifactContentStoreConfig;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcArtifactStore;
import dev.horizen.agent.tools.browser.SandboxBrowserTool;
import dev.horizen.agent.tools.vision.VisionAnalyzeTool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * 显式启用的完整验收：浏览器截图 → JDBC 引用 → BOS 产物。
 */
@Tag("live-sandbox")
class E2bBrowserArtifactLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void publishesSandboxScreenshotAsArtifact() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("horizen.e2b.browser.artifact.live"));
        String visionModelName = required("browser.live.model.name");
        HikariDataSource dataSource = dataSource();
        String owner = "browser-live-" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        BosArtifactContentStore contents = bos();
        ArtifactLifecycleService lifecycle =
                new ArtifactLifecycleService(new JdbcArtifactStore(dataSource), contents);
        HttpE2bSandbox sandbox = sandbox();
        String artifactId = null;
        try {
            sandbox.start();
            SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
            filesystem.setSandbox(sandbox);
            ArtifactEventCollector events = new ArtifactEventCollector();
            RuntimeContext context =
                    RuntimeContext.builder()
                            .userId(owner)
                            .sessionId("browser-artifact")
                            .put(AbstractFilesystem.class, filesystem)
                            .put(
                                    SandboxAcquireResult.class,
                                    SandboxAcquireResult.userManaged(sandbox))
                            .put(
                                    ArtifactExecutionContext.class,
                                    new ArtifactExecutionContext("browser-screenshot"))
                            .put(ArtifactEventCollector.class, events)
                            .build();
            List<SandboxBrowserTool> tools = SandboxBrowserTool.createAll(lifecycle);
            call(
                    tools.get(0),
                    context,
                    Map.of("url", "data:text/html,<h1>Horizen screenshot</h1>"));
            String output = text(call(tools.get(11), context, Map.of()));
            assertTrue(output.trim().startsWith("{"), output);
            artifactId = JSON.readTree(output).path("artifact_id").asText();
            var artifact = new JdbcArtifactStore(dataSource).find(owner, artifactId).orElseThrow();
            assertEquals(ArtifactState.READY, artifact.getState());
            byte[] png = contents.get(artifact.getContentRef());
            assertTrue(png.length > 8 && png[0] == (byte) 0x89 && png[1] == 0x50);
            assertEquals(1, events.drain().size());
            OpenAIChatModel model =
                    OpenAIChatModel.builder()
                            .apiKey(required("browser.live.model.apiKey"))
                            .baseUrl(required("browser.live.model.baseUrl"))
                            .modelName(visionModelName)
                            .stream(true)
                            .formatter(new OpenAIChatFormatter())
                            .build();
            String visionText =
                    text(
                            new VisionAnalyzeTool(
                                    model, new JdbcArtifactStore(dataSource), contents)
                                    .callAsync(
                                            ToolCallParam.builder()
                                                    .runtimeContext(context)
                                                    .input(
                                                            Map.of(
                                                                    "artifact_id",
                                                                    artifactId,
                                                                    "question",
                                                                    "图片中最明显的标题文字是什么？"))
                                                    .build())
                                    .block());
            assertTrue(
                    !visionText.isBlank() && !visionText.toLowerCase().contains("error"),
                    visionText);
            contents.delete(artifact.getContentRef());
        } finally {
            sandbox.shutdown();
            jdbc.update("DELETE FROM ha_conversation_history WHERE owner_key = ?", owner);
            jdbc.update("DELETE FROM ha_artifact WHERE owner_key = ?", owner);
            dataSource.close();
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

    private static HikariDataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(required("browser.live.jdbc.url"));
        ds.setUsername(required("browser.live.jdbc.username"));
        ds.setPassword(required("browser.live.jdbc.password"));
        return ds;
    }

    private static BosArtifactContentStore bos() {
        return new BosArtifactContentStore(
                new BosArtifactContentStoreConfig(
                        required("browser.live.bos.endpoint"),
                        required("browser.live.bos.bucket"),
                        required("browser.live.bos.accessKey"),
                        required("browser.live.bos.secretKey"),
                        required("browser.live.bos.prefix"),
                        104857600));
    }

    private static HttpE2bSandbox sandbox() {
        HttpE2bSandboxClientOptions o = new HttpE2bSandboxClientOptions();
        o.setApiKey(required("browser.live.e2b.apiKey"));
        o.setApiBaseUrl(required("browser.live.e2b.apiBaseUrl"));
        o.setRuntimeBaseUrlPattern(required("browser.live.e2b.runtime"));
        o.setTemplateId(required("browser.live.e2b.template"));
        o.setWorkspaceRoot("/tmp/horizen-agent");
        o.setSandboxTimeoutSeconds(300);
        return (HttpE2bSandbox)
                new HttpE2bSandboxClient(o, null)
                        .create(new WorkspaceSpec(), new NoopSnapshotSpec(), o);
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = environmentValue(name);
        if (value == null || value.isBlank()) value = configuredValue(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), "missing " + name);
        return value.trim();
    }

    private static String environmentValue(String name) {
        return switch (name) {
            case "browser.live.model.apiKey" -> {
                String vision = LiveConfiguration.environment("AGENT_VISION_API_KEY");
                yield vision == null || vision.isBlank() ? LiveConfiguration.environment("ARK_API_KEY") : vision;
            }
            case "browser.live.model.baseUrl" -> {
                String vision = LiveConfiguration.environment("AGENT_VISION_BASE_URL");
                String primary = LiveConfiguration.environment("ARK_BASE_URL");
                yield vision == null || vision.isBlank()
                        ? (primary == null || primary.isBlank() ? "https://ark.cn-beijing.volces.com/api/v3" : primary) : vision;
            }
            case "browser.live.model.name" -> LiveConfiguration.environment("AGENT_VISION_MODEL");
            case "browser.live.e2b.apiKey" -> LiveConfiguration.environment("AGENT_E2B_API_KEY");
            case "browser.live.e2b.apiBaseUrl" -> LiveConfiguration.environment("AGENT_E2B_API_BASE_URL");
            case "browser.live.e2b.runtime" -> LiveConfiguration.environment("AGENT_E2B_RUNTIME_BASE_URL_PATTERN");
            case "browser.live.e2b.template" -> LiveConfiguration.environment("AGENT_E2B_TEMPLATE_ID");
            default -> null;
        };
    }

    private static String configuredValue(String name) {
        String key;
        String file;
        switch (name) {
            case "browser.live.jdbc.url" -> {
                file = ".env.yml";
                key = "horizen.agent.storage.jdbc-url";
            }
            case "browser.live.jdbc.username" -> {
                file = ".env.yml";
                key = "horizen.agent.storage.jdbc-username";
            }
            case "browser.live.jdbc.password" -> {
                file = ".env.yml";
                key = "horizen.agent.storage.jdbc-password";
            }
            case "browser.live.bos.endpoint" -> {
                file = ".env.yml";
                key = "horizen.agent.artifact.bos.endpoint";
            }
            case "browser.live.bos.bucket" -> {
                file = ".env.yml";
                key = "horizen.agent.artifact.bos.bucket";
            }
            case "browser.live.bos.accessKey" -> {
                file = ".env.yml";
                key = "horizen.agent.artifact.bos.access-key";
            }
            case "browser.live.bos.secretKey" -> {
                file = ".env.yml";
                key = "horizen.agent.artifact.bos.secret-key";
            }
            case "browser.live.bos.prefix" -> {
                file = ".env.yml";
                key = "horizen.agent.artifact.bos.key-prefix";
            }
            default -> {
                return null;
            }
        }
        Path path = Path.of("..", file).normalize();
        if (!Files.isRegularFile(path)) return null;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        } catch (Exception error) {
            return null;
        }
        return values.getProperty(key);
    }
}
