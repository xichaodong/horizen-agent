package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import dev.horizen.agent.adapter.workspace.horizen.HorizenAgentReleaseRepository;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionWorkspaceReleaseRepository;
import dev.horizen.agent.web.AgentWebApplication;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.interaction.InteractionApi;
import dev.horizen.agent.web.api.session.SessionApi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用两个真实宿主、Redis 4、H2 和 HTTP 发布，恢复或新建 Turn 均不改变 Session 的版本。
 */
@EnabledIfSystemProperty(named = "horizen.redis.live", matches = "true")
class PublishedSessionRecoveryLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(10);
    @TempDir Path root;

    @Test
    void approvalResumesTheOriginalPublicationAfterRestartAndR2Publication() throws Exception {
        scenario(true);
    }

    @Test
    void clarificationResumesTheOriginalPublicationAfterRestartAndR2Publication() throws Exception {
        scenario(false);
    }

    private void scenario(boolean approval) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var current = new AtomicInteger(1);
        Map<String, byte[]> objects = new HashMap<>();
        Map<Integer, Map<String, Object>> manifests =
                Map.of(1, manifest(endpoint, 1, objects), 2, manifest(endpoint, 2, objects));
        List<Long> loaded = Collections.synchronizedList(new ArrayList<>());
        server.createContext(
                "/current",
                e -> {
                    assertEquals(
                            "Bearer synthetic-test-token",
                            e.getRequestHeaders().getFirst("Authorization"));
                    byte[] bytes =
                            JSON.writeValueAsBytes(
                                    Map.of("code", 200, "data", manifests.get(current.get())));
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.createContext(
                "/by-id",
                e -> {
                    assertEquals(
                            "Bearer synthetic-test-token",
                            e.getRequestHeaders().getFirst("Authorization"));
                    long id = JSON.readTree(e.getRequestBody()).path("releaseId").asLong();
                    loaded.add(id);
                    if (approval)
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    byte[] bytes =
                            JSON.writeValueAsBytes(
                                    Map.of("code", 200, "data", manifests.get((int) id)));
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.createContext(
                "/objects/",
                e -> {
                    byte[] bytes = objects.get(e.getRequestURI().getPath());
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String url =
                "jdbc:h2:mem:publication_"
                        + suffix
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String redis = "published-session-test:" + suffix + ":";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(dataSource);
        var binding = new JdbcSessionWorkspaceReleaseRepository(dataSource);
        var identity = new ExecutionIdentity("owner-" + suffix, "actor");
        String askId = null;
        String turnId;
        try {
            try (var first = host(url, redis, endpoint, root.resolve("a"), "a")) {
                var service = first.getBean(AgentService.class);
                var events =
                        service.streamChat(
                                        identity,
                                        new ChatApi.ChatRequest(
                                                "old-session",
                                                approval ? "approval pinned" : "ask pinned",
                                                "first",
                                                List.of()))
                                .collectList()
                                .block(WAIT);
                assertTrue(
                        events.stream()
                                .anyMatch(
                                        e ->
                                                (approval
                                                                ? "approval_required"
                                                                : "ask_user_required")
                                                        .equals(e.getType())),
                        events.toString());
                turnId = service.sessionExecution(identity, "old-session").getCurrentTurnId();
                if (!approval)
                    askId =
                            JSON.readTree(
                                            events.stream()
                                                    .filter(
                                                            e ->
                                                                    "ask_user_required"
                                                                            .equals(e.getType()))
                                                    .findFirst()
                                                    .orElseThrow()
                                                    .getDetails())
                                    .path("askUserId")
                                    .asText();
                assertEquals(
                        1,
                        binding.find(identity.getOwnerKey(), "old-session")
                                .orElseThrow()
                                .getReleaseId());
            }
            current.set(2);
            HorizenAgentReleaseRepository.delete(root.resolve("a"));
            try (var second = host(url, redis, endpoint, root.resolve("b"), "b")) {
                var service = second.getBean(AgentService.class);
                if (approval) {
                    var pending =
                            service.pendingApprovals(
                                    identity,
                                    new InteractionApi.ApprovalQueryRequest("old-session", turnId));
                    service.decideApproval(
                            identity,
                            new InteractionApi.ApprovalDecisionRequest(
                                    "old-session",
                                    turnId,
                                    List.of(
                                            new InteractionApi.ApprovalChoice(
                                                    pending.getApprovals().get(0).getApprovalId(),
                                                    true))));
                } else
                    service.answerAskUser(
                            identity,
                            new InteractionApi.AskUserAnswerRequest(
                                    askId,
                                    List.of(
                                            Map.of(
                                                    "questionId",
                                                    "scope",
                                                    "selectedOptionIds",
                                                    List.of("week"),
                                                    "customText",
                                                    "")),
                                    false));
                var resumed =
                        service.subscribeSession(
                                        identity,
                                        new SessionApi.SessionSubscribeRequest(
                                                "old-session", turnId))
                                .collectList()
                                .block(WAIT);
                assertTrue(
                        resumed.stream().anyMatch(e -> "done".equals(e.getType())),
                        resumed.toString());
                assertEquals(
                        1,
                        binding.find(identity.getOwnerKey(), "old-session")
                                .orElseThrow()
                                .getReleaseId());
                service.streamChat(
                                identity,
                                new ChatApi.ChatRequest(
                                        "old-session", "next Turn", "second", List.of()))
                        .collectList()
                        .block(WAIT);
                assertEquals(
                        1,
                        binding.find(identity.getOwnerKey(), "old-session")
                                .orElseThrow()
                                .getReleaseId());
                service.streamChat(
                                identity,
                                new ChatApi.ChatRequest(
                                        "new-session", "new Session", "third", List.of()))
                        .collectList()
                        .block(WAIT);
                assertEquals(
                        2,
                        binding.find(identity.getOwnerKey(), "new-session")
                                .orElseThrow()
                                .getReleaseId());
                assertTrue(loaded.size() >= 2);
                assertTrue(loaded.stream().allMatch(id -> id == 1));
                var old = manifests.get(1).get("releaseHash").toString();
                assertEquals(
                        "AGENTS R1",
                        Files.readString(
                                root.resolve("b/cache").resolve(old).resolve("assets/AGENTS.md")));
                assertEquals(
                        2,
                        service.sessionMessages(identity, "old-session").getMessages().stream()
                                .filter(m -> "assistant".equalsIgnoreCase(m.getRole()))
                                .count());
            }
            System.out.println(
                    "Publication Session recovery: "
                            + (approval ? "approval" : "clarification")
                            + " on B kept R1; new Session selected R2");
        } finally {
            server.stop(0);
            try (var connection = new JedisPooled(redisUrl())) {
                String cursor = "0";
                do {
                    var page =
                            connection.scan(cursor, new ScanParams().match(redis + "*").count(100));
                    for (String key : page.getResult()) connection.del(key);
                    cursor = page.getCursor();
                } while (!"0".equals(cursor));
            }
        }
    }

    private ConfigurableApplicationContext host(
            String url, String redis, String endpoint, Path cache, String instance) {
        Map<String, Object> props = new HashMap<>();
        props.put("horizen.agent.model-mode", "SCRIPTED");
        props.put("horizen.agent.api-key", "");
        props.put("horizen.agent.stream-timeout", "20s");
        props.put("horizen.agent.storage.mode", "DISTRIBUTED");
        props.put("horizen.agent.storage.jdbc-url", url);
        props.put("horizen.agent.storage.lease-ttl", "1s");
        props.put("horizen.agent.storage.lease-heartbeat", "100ms");
        props.put("horizen.agent.storage.control-poll-interval", "100ms");
        props.put("horizen.agent.storage.jdbc-username", "sa");
        props.put("horizen.agent.storage.jdbc-password", "");
        props.put("horizen.agent.storage.redis-url", redisUrl());
        props.put("horizen.agent.storage.redis-key-prefix", redis);
        props.put("horizen.agent.storage.instance-id", instance);
        props.put("horizen.agent.gateway.mode", "remote");
        props.put("horizen.agent.gateway.url", "");
        props.put("horizen.agent.gateway.token", "");
        props.put("horizen.trace.enabled", false);
        props.put("horizen.agent.skill-release.enabled", false);
        props.put("horizen.agent.workspace-release.enabled", true);
        props.put("horizen.agent.workspace-release.project-id", 7);
        props.put("horizen.agent.workspace-release.endpoint", endpoint + "/current");
        props.put("horizen.agent.workspace-release.token", "synthetic-test-token");
        props.put("horizen.agent.workspace-release.artifact-hosts", "127.0.0.1");
        props.put("horizen.agent.workspace-release.allow-http", true);
        props.put("horizen.agent.workspace-release.cache-directory", cache.toString());
        props.put("horizen.agent.sandbox.e2b.enabled", false);
        props.put("horizen.agent.sandbox.snapshot.bos.enabled", false);
        props.put("horizen.agent.artifact.bos.enabled", false);
        return new SpringApplicationBuilder(
                        AgentWebApplication.class, WorkspaceTestConfiguration.class)
                .web(WebApplicationType.NONE)
                .initializers(
                        c ->
                                c.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(new MapPropertySource("publication-test", props)))
                .run();
    }

    private static String redisUrl() {
        return System.getProperty("horizen.redis.url", "redis://127.0.0.1:6379");
    }

    private Map<String, Object> manifest(String endpoint, int version, Map<String, byte[]> objects)
            throws Exception {
        byte[] text = ("AGENTS R" + version).getBytes(StandardCharsets.UTF_8);
        String asset = hash(text);
        objects.put("/objects/" + asset, text);
        String skills = hash(new byte[0]);
        String workspace =
                hash(
                        ("7\nhorizen-web-agent\nAGENTS.md\0"
                                        + asset
                                        + "\0"
                                        + text.length
                                        + "\n"
                                        + skills)
                                .getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "projectId",
                7,
                "agentKey",
                "horizen-web-agent",
                "releaseId",
                version,
                "releaseNo",
                version,
                "releaseHash",
                workspace,
                "assets",
                List.of(
                        Map.of(
                                "path",
                                "AGENTS.md",
                                "url",
                                endpoint + "/objects/" + asset,
                                "sha256",
                                asset,
                                "size",
                                text.length,
                                "mediaType",
                                "text/plain")),
                "skillRelease",
                Map.of(
                        "projectId",
                        7,
                        "releaseId",
                        version,
                        "releaseNo",
                        version,
                        "releaseHash",
                        skills,
                        "skillCount",
                        0,
                        "items",
                        List.of()));
    }

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
