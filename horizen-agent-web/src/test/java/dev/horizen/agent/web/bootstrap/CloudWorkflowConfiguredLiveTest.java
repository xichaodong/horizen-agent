package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;

import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.bos.BosArtifactContentStore;
import dev.horizen.agent.web.AgentWebApplication;
import dev.horizen.agent.web.api.AgentController;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.artifact.ArtifactApi;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.interaction.InteractionApi;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.config.ArtifactProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.RuntimeStorageProperties;
import dev.horizen.agent.web.config.WorkspaceStorageProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import redis.clients.jedis.JedisPooled;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 真实云端任务验收，覆盖人工暂停、观察者断连、跨宿主恢复和 BOS 输出。
 */
@EnabledIfSystemProperty(named = "horizen.cloud.workflow.live", matches = "true")
class CloudWorkflowConfiguredLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(120);
    private static final String CSV = "marker,scope,value\nHZ_CLOUD_ACCEPTANCE_20261003,full,42\n";

    @Test
    void delegatedClarificationApprovalExecutionAndArtifactSurviveObserverRefreshAndHostSwitch()
            throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String prefix = "horizen-cloud-workflow:" + suffix + ":";
        var identity = new ExecutionIdentity("cloud-workflow-" + suffix, "acceptance");
        String session = "cloud-workflow-" + suffix;
        var calls = new AtomicInteger();
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newFixedThreadPool(2);
        provider.setExecutor(workers);
        provider.createContext(
                "/tools/list",
                exchange -> {
                    byte[] body =
                            """
                                    {"protocolVersion":1,"status":"success","tools":[{"name":"acceptance_record_decision",
                                    "description":"记录合成验收的编辑范围，仅返回模拟回执，无业务动作，必须审批后调用。",
                                    "inputSchema":{"type":"object","properties":{"scope":{"type":"string","enum":["full","tags_only"]}},"required":["scope"],"additionalProperties":false},
                                    "readOnly":false,"riskLevel":"high","approvalPolicy":"required","timeoutSeconds":10,
                                    "idempotent":false,"concurrencySafe":false,"groupId":"acceptance","groupActiveByDefault":true}]}
                                    """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        provider.createContext(
                "/tools/invoke",
                exchange -> {
                    var request = JSON.readTree(exchange.getRequestBody());
                    assertEquals("acceptance_record_decision", request.path("toolName").asText());
                    assertEquals("full", request.path("input").path("scope").asText());
                    calls.incrementAndGet();
                    byte[] body =
                            "{\"protocolVersion\":1,\"status\":\"success\",\"data\":{\"confirmedScope\":\"full\"}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        provider.start();
        try (var first = host(prefix, "workflow-a", identity, provider.getAddress().getPort());
             var second =
                     host(prefix, "workflow-b", identity, provider.getAddress().getPort())) {
            var a = first.getBean(AgentService.class);
            var b = second.getBean(AgentService.class);
            var storage = first.getBean(RuntimeStorageProperties.class);
            var artifacts = first.getBean(ArtifactProperties.class);
            var workspace = first.getBean(WorkspaceStorageProperties.class);
            assertTrue(a.status().isReady());
            assertTrue(artifacts.isEnabled());
            assertTrue(first.getBean(E2bSandboxProperties.class).isEnabled());
            try {
                // 仅取消此观察者，宿主管理的任务仍需继续执行到提问阶段。
                var initial =
                        request(
                                first,
                                "/api/chat/stream",
                                Map.of(
                                        "sessionId",
                                        session,
                                        "message",
                                        prompt(),
                                        "requestId",
                                        "workflow-request",
                                        "artifactIds",
                                        List.of()));
                try (var input = initial.body()) {
                    assertTrue(input.read() >= 0);
                }
                until(
                        () ->
                                Set.of(
                                                "waiting_ask_user",
                                                "failed",
                                                "completed",
                                                "waiting_approval")
                                        .contains(
                                                b.sessionExecution(identity, session).getStatus()),
                        WAIT);
                var current = b.sessionExecution(identity, session);
                assertEquals(
                        "waiting_ask_user",
                        current.getStatus(),
                        "The first pause must be the delegated clarification");
                var paused = subscribe(second, session, current.getCurrentTurnId());
                var questionEvent =
                        paused.stream()
                                .filter(event -> "ask_user_required".equals(event.getType()))
                                .findFirst()
                                .orElseThrow();
                var question = JSON.readTree(questionEvent.getDetails());
                assertEquals(session, question.path("sessionId").asText());
                assertTrue(
                        b.sessionMessages(identity, session).getTimelineEvents().stream()
                                .anyMatch(
                                        item ->
                                                "subagent_start"
                                                        .equals(item.getEvent().getType())));
                assertEquals(0, calls.get());
                idle(first);
                first.close();
                assertEquals(
                        "waiting_ask_user",
                        b.sessionExecution(identity, session).getStatus(),
                        "Stopping A must preserve the waiting task");
                JsonNode definition = JSON.readTree(question.path("questionsJson").asText()).get(0);
                assertTrue(definition.path("options").toString().contains("full"));
                b.answerAskUser(
                        identity,
                        new InteractionApi.AskUserAnswerRequest(
                                question.path("askUserId").asText(),
                                List.of(
                                        Map.of(
                                                "questionId",
                                                definition.path("questionId").asText(),
                                                "selectedOptionIds",
                                                List.of("full"),
                                                "customText",
                                                "")),
                                false));
                var approvalEvents = subscribe(second, session, current.getCurrentTurnId());
                assertTrue(
                        approvalEvents.stream()
                                .anyMatch(event -> "approval_required".equals(event.getType())));
                var approvals =
                        b.pendingApprovals(
                                        identity,
                                        new InteractionApi.ApprovalQueryRequest(
                                                session, current.getCurrentTurnId()))
                                .getApprovals();
                assertEquals(1, approvals.size());
                assertEquals("acceptance_record_decision", approvals.get(0).getToolName());
                assertEquals(0, calls.get(), "The synthetic provider must not run before approval");
                b.decideApproval(
                        identity,
                        new InteractionApi.ApprovalDecisionRequest(
                                session,
                                current.getCurrentTurnId(),
                                List.of(
                                        new InteractionApi.ApprovalChoice(
                                                approvals.get(0).getApprovalId(), true))));
                var completed = subscribe(second, session, current.getCurrentTurnId());
                assertTrue(
                        completed.stream().anyMatch(event -> "done".equals(event.getType())),
                        diagnostics(completed));
                assertEquals(1, calls.get());
                try (var restarted =
                             host(
                                     prefix,
                                     "workflow-a-restarted",
                                     identity,
                                     provider.getAddress().getPort())) {
                    var restored =
                            restarted
                                    .getBean(AgentService.class)
                                    .sessionMessages(identity, session);
                    var card =
                            restored.getPresentations().stream()
                                    .filter(item -> "artifact_card".equals(item.getType()))
                                    .findFirst()
                                    .orElseThrow();
                    String artifactId = String.valueOf(card.getData().get("artifactId"));
                    var download =
                            b.artifactDownloadUrl(
                                    identity,
                                    new ArtifactApi.ArtifactDownloadRequest(artifactId, 300));
                    var response =
                            HttpClient.newHttpClient()
                                    .send(
                                            HttpRequest.newBuilder(URI.create(download.getUrl()))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString(
                                                    StandardCharsets.UTF_8));
                    assertEquals(200, response.statusCode());
                    assertEquals(CSV, response.body().replace("\r\n", "\n"));
                    assertTrue(
                            restored.getTimelineEvents().stream()
                                    .anyMatch(
                                            item ->
                                                    "subagent_result"
                                                            .equals(item.getEvent().getType())));
                    assertTrue(
                            restored.getTimelineEvents().stream()
                                    .noneMatch(
                                            item ->
                                                    Set.of("tool_input_delta", "tool_output_delta")
                                                            .contains(item.getEvent().getType())));
                    assertTrue(
                            b.sessionMessages(
                                            new ExecutionIdentity("other-" + suffix, "other"),
                                            session)
                                    .getTimelineEvents()
                                    .isEmpty());
                    idle(restarted);
                    idle(second);
                    assertTrue(
                            second.getBean(AgentController.class)
                                    .streamStatus()
                                    .getTotalConnections()
                                    >= 3,
                            "Real SSE connections must have been exercised");
                    System.out.println(
                            "Cloud workflow live: real SSE disconnect -> delegated question -> A shutdown -> B"
                                    + " answer/approval -> one invoke -> E2B CSV/BOS -> restarted A history ->"
                                    + " resources idle passed");
                }
            } finally {
                try {
                    var running = b.sessionExecution(identity, session);
                    if (running.getCurrentTurnId() != null
                            && Set.of(
                                    "running",
                                    "waiting_approval",
                                    "waiting_ask_user",
                                    "cancelling")
                            .contains(running.getStatus())) {
                        b.cancelSession(
                                identity,
                                new SessionApi.SessionCancelRequest(
                                        session, running.getCurrentTurnId()));
                    }
                } finally {
                    second.close();
                    if (first.isActive()) first.close();
                    cleanup(storage, artifacts, workspace, identity.getOwnerKey(), prefix);
                }
            }
        } finally {
            provider.stop(0);
            workers.shutdownNow();
        }
    }

    private static ConfigurableApplicationContext host(
            String prefix, String instance, ExecutionIdentity owner, int port) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("horizen.agent.model-mode", "REMOTE");
        properties.put("horizen.agent.storage.mode", "DISTRIBUTED");
        properties.put("horizen.agent.storage.redis-key-prefix", prefix);
        properties.put("horizen.agent.storage.instance-id", instance);
        properties.put("horizen.agent.identity.owner-key", owner.getOwnerKey());
        properties.put("horizen.agent.identity.actor-id", owner.getActorId());
        properties.put("horizen.agent.gateway.mode", "remote");
        properties.put("horizen.agent.gateway.url", "http://127.0.0.1:" + port + "/");
        properties.put("horizen.agent.gateway.token", "synthetic-workflow-token");
        properties.put("horizen.agent.gateway.allowed-tools", "acceptance_record_decision");
        properties.put("horizen.agent.workspace-release.enabled", false);
        properties.put("horizen.agent.skill-release.enabled", false);
        properties.put("horizen.agent.sandbox.e2b.enabled", true);
        properties.put("horizen.agent.sandbox.snapshot.bos.enabled", false);
        properties.put("horizen.agent.artifact.bos.enabled", true);
        properties.put("horizen.trace.enabled", false);
        properties.put("horizen.agent.stream-timeout", "120s");
        properties.put("horizen.agent.idle-timeout", "90s");
        properties.put("server.port", 0);
        return new SpringApplicationBuilder(AgentWebApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "cloud-workflow-test", properties)))
                .run();
    }

    private static String prompt() {
        return """
                这是合成整体验收。请严格按阶段执行：
                1. 必须先用 agent_spawn 委派 general_worker。让它调用 ask_user，只提出一题编辑范围单选题，
                   questionId=edit_scope，两个 optionId 必须是 full 和 tags_only，required=true。
                   子 Agent 不读文件、不运行代码、不调用其他工具；需要交互时交给主 Agent。
                2. 主 Agent 接到子任务需求后调用 ask_user，并等待我的回答，不得自己选择。
                3. 得到我的选择后，主 Agent 调用 acceptance_record_decision，scope 为我选择的 optionId，等待真实审批流程。
                   未审批时，不得创建文件；这个工具只返回合成回执。
                4. 工具获批成功后，使用 execute 在沙箱中运行 Python，创建 outputs/cloud-acceptance.csv。
                   UTF-8 文件内容必须精确为：
                   marker,scope,value
                   HZ_CLOUD_ACCEPTANCE_20261003,full,42
                   文件末尾必须有换行；禁止联网，不使用业务工具，不读写工作区之外的路径。
                5. 调用 deliver_artifact，filePath=outputs/cloud-acceptance.csv，fileName=cloud-acceptance.csv。
                   最后简短说明已交付。不要保存长期记忆或读取 Skill。
                """;
    }

    private static void until(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Acceptance condition timed out");
            Thread.sleep(100);
        }
    }

    private static void idle(ConfigurableApplicationContext host) throws Exception {
        var controller = host.getBean(AgentController.class);
        until(
                () -> {
                    var status = controller.streamStatus();
                    return status.getActiveConnections() == 0
                            && status.getQueuedEvents() == 0
                            && status.getQueuedBytes() == 0
                            && status.getWriterActiveThreads() == 0
                            && status.getWriterQueueSize() == 0
                            && status.getDatabasePool().getActiveConnections() == 0
                            && status.getRedisPolling().getActivePollers() == 0
                            && status.getLeaseRenewal().getTrackedTurns() == 0
                            && status.getLeaseRenewal().getActiveBatches() == 0;
                },
                Duration.ofSeconds(10));
    }

    private static HttpResponse<InputStream> request(
            ConfigurableApplicationContext host, String path, Map<String, Object> body)
            throws Exception {
        int port = ((ServletWebServerApplicationContext) host).getWebServer().getPort();
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(WAIT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response;
    }

    private static List<ChatApi.ChatStreamEvent> subscribe(
            ConfigurableApplicationContext host, String session, String turn) throws Exception {
        var response =
                request(
                        host,
                        "/api/session/subscribe",
                        Map.of("sessionId", session, "expectedTurnId", turn));
        List<ChatApi.ChatStreamEvent> events = new ArrayList<>();
        try (var reader =
                     new BufferedReader(
                             new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("data:") && !line.substring(5).isBlank()) {
                    events.add(
                            JSON.readValue(
                                    line.substring(5).strip(), ChatApi.ChatStreamEvent.class));
                }
            }
        }
        return events;
    }

    private static String diagnostics(List<ChatApi.ChatStreamEvent> events) {
        return events.stream()
                .filter(event -> Set.of("error", "tool_end").contains(event.getType()))
                .map(
                        event ->
                                event.getType()
                                        + ":"
                                        + event.getToolName()
                                        + ":"
                                        + event.getStatus()
                                        + ":"
                                        + event.getDetails())
                .toList()
                .toString();
    }

    private static void cleanup(
            RuntimeStorageProperties storage,
            ArtifactProperties artifacts,
            WorkspaceStorageProperties workspace,
            String owner,
            String prefix) {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                storage.getJdbcUrl(),
                                storage.getJdbcUsername(),
                                storage.getJdbcPassword()));
        List<String> content =
                jdbc.queryForList(
                        "SELECT content_ref FROM ha_artifact WHERE owner_key=? AND content_ref IS NOT NULL",
                        String.class,
                        owner);
        List<String> snapshots =
                jdbc.queryForList(
                        "SELECT snapshot_id FROM ha_session WHERE owner_key=? AND snapshot_id IS NOT NULL",
                        String.class,
                        owner);
        for (String table :
                List.of(
                        "ha_conversation_history",
                        "ha_interaction",
                        "ha_turn",
                        "ha_session",
                        "ha_artifact",
                        "ha_workspace_operation",
                        "ha_workspace_file"))
            jdbc.update("DELETE FROM " + table + " WHERE owner_key=?", owner);
        var bos = new BosArtifactContentStore(artifacts.toConfig());
        content.forEach(bos::delete);
        var snapshotConfig = workspace.directoryConfig(64L * 1024 * 1024);
        var archives = new BosArtifactContentStore(snapshotConfig);
        snapshots.forEach(id -> archives.delete(snapshotConfig.getKeyPrefix() + "/" + id + ".tar"));
        try (var redis = new JedisPooled(URI.create(storage.getRedisUrl()))) {
            Set<String> keys = redis.keys(prefix + "*");
            if (!keys.isEmpty()) redis.del(keys.toArray(String[]::new));
        }
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ha_session WHERE owner_key=?", Integer.class, owner));
    }
}
