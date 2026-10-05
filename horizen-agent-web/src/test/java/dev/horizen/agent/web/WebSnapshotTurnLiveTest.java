package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.*;

import com.baidubce.auth.DefaultBceCredentials;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.BosClientConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcWorkspaceSnapshotPointerRepository;

import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 使用两个真实应用 JVM 与 HTTP/SSE，连接真实 E2B、BOS 和 Redis，模型回复使用合成数据。 */
@EnabledIfSystemProperty(named = "horizen.snapshot.web.live", matches = "true")
class WebSnapshotTurnLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT = "horizen-web-agent";
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @TempDir Path root;

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aCreatesFilesThenAfterAExitsBRestoresUpdatesAndStartsAnotherTurn() throws Exception {
        Properties sandbox = load(".env.yml");
        Properties bos = load(".env.yml");
        Properties storage = load(".env.yml");
        String run = UUID.randomUUID().toString().replace("-", "");
        String owner = "web-snapshot-owner-" + run;
        String prefix = "horizen-web-snapshot-live:" + run + ":";
        String objectPrefix =
                required(bos, "horizen.agent.artifact.bos.key-prefix")
                        + "/web-snapshot-acceptance/"
                        + run;
        String database = "web_snapshot_" + run;
        String localJdbc =
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        boolean mysql = Boolean.getBoolean("horizen.snapshot.mysql.live");
        DriverManagerDataSource dataSource =
                mysql
                        ? new DriverManagerDataSource(
                                required(storage, "horizen.agent.storage.jdbc-url"),
                                required(storage, "horizen.agent.storage.jdbc-username"),
                                required(storage, "horizen.agent.storage.jdbc-password"))
                        : new DriverManagerDataSource(localJdbc, "sa", "");
        Server sql = null;
        String jdbc;
        if (mysql) {
            jdbc = required(storage, "horizen.agent.storage.jdbc-url");
            new JdbcTemplate(dataSource)
                    .query("SELECT snapshot_id FROM ha_session WHERE 1=0", rs -> {});
            System.out.println(
                    "Web snapshot live: configured real MySQL enabled; test rows use a unique owner");
        } else {
            new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                    .execute(dataSource);
            sql = Server.createTcpServer("-tcpPort", "0", "-tcpDaemon").start();
            jdbc =
                    "jdbc:h2:tcp://127.0.0.1:"
                            + sql.getPort()
                            + "/mem:"
                            + database
                            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        }
        Set<String> snapshots = new LinkedHashSet<>();
        try (LiveEndpoints endpoints = new LiveEndpoints(sandbox);
                JedisPooled redis =
                        new JedisPooled(
                                URI.create(required(storage, "horizen.agent.storage.redis-url")))) {
            Child a = null;
            Child b = null;
            var pointers = new JdbcWorkspaceSnapshotPointerRepository(dataSource);
            var workspaceIdentity = new WorkspaceSnapshotKey(owner, "conversation");
            var otherWorkspace = new WorkspaceSnapshotKey(owner, "other-conversation");
            try {
                Properties config =
                        configuration(
                                sandbox,
                                bos,
                                storage,
                                owner,
                                prefix,
                                objectPrefix,
                                jdbc,
                                endpoints);
                config.setProperty("horizen.agent.storage.jdbc-username", dataSource.getUsername());
                config.setProperty("horizen.agent.storage.jdbc-password", dataSource.getPassword());
                config.setProperty("horizen.agent.storage.jdbc-maximum-pool-size", "4");
                a = start("instance-a", config);
                System.out.println(
                        "Web snapshot live: independent JVM A listening; production snapshot configuration"
                                + " enabled");
                var parallel = Executors.newFixedThreadPool(2);
                try {
                    int port = a.port;
                    var main =
                            parallel.submit(
                                    () ->
                                            chat(
                                                    port,
                                                    "conversation",
                                                    "snapshot-create",
                                                    "request-a"));
                    var other =
                            parallel.submit(
                                    () ->
                                            chat(
                                                    port,
                                                    "other-conversation",
                                                    "snapshot-other-create",
                                                    "request-other"));
                    assertReply(main.get(), "verified:snapshot-create");
                    assertReply(other.get(), "verified:snapshot-other-create");
                } finally {
                    parallel.shutdownNow();
                }
                String snapshotId = pointers.findSnapshotId(workspaceIdentity).orElseThrow();
                String otherSnapshot = pointers.findSnapshotId(otherWorkspace).orElseThrow();
                assertNotEquals(snapshotId, otherSnapshot);
                snapshots.add(snapshotId);
                snapshots.add(otherSnapshot);
                assertStatus(a.port, "conversation", "completed");
                stop(a);
                assertFalse(a.process.isAlive());
                cleanupRedis(redis, prefix);
                System.out.println(
                        "Web snapshot live: A Turn 1 completed over HTTP/SSE and A JVM stopped");

                b = start("instance-b", config);
                assertReply(
                        chat(b.port, "conversation", "snapshot-update", "request-b"),
                        "verified:snapshot-update");
                assertStatus(b.port, "conversation", "completed");
                assertEquals(snapshotId, pointers.findSnapshotId(workspaceIdentity).orElseThrow());
                System.out.println(
                        "Web snapshot live: B Turn 2 restored A files using durable SQL pointer after Redis was"
                                + " cleared");
                cleanupRedis(redis, prefix);
                assertReply(
                        chat(b.port, "conversation", "snapshot-read", "request-c"),
                        "verified:snapshot-read");
                assertStatus(b.port, "conversation", "completed");
                assertEquals(snapshotId, pointers.findSnapshotId(workspaceIdentity).orElseThrow());
                assertReply(
                        chat(
                                b.port,
                                "other-conversation",
                                "snapshot-other-read",
                                "request-other-read"),
                        "verified:snapshot-other-read");
                assertEquals(otherSnapshot, pointers.findSnapshotId(otherWorkspace).orElseThrow());
                JsonNode history =
                        post(
                                b.port,
                                "/api/session/messages/query",
                                Map.of("sessionId", "conversation"));
                assertTrue(history.toString().contains("snapshot-create"));
                assertTrue(history.toString().contains("snapshot-update"));
                assertTrue(history.toString().contains("snapshot-read"));
                assertTrue(
                        endpoints.created.size() >= 5,
                        "Every test turn must have a new real sandbox");
                JsonNode status = get(b.port, "/api/stream/status");
                assertEquals(0, status.path("activeConnections").asInt(-1));
                System.out.println(
                        "Web snapshot live: separate Sessions retained different report.md files; cross-turn"
                                + " restore and SSE release verified");
            } finally {
                try {
                    if (a != null) stop(a);
                    if (b != null) stop(b);
                    pointers.findSnapshotId(workspaceIdentity).ifPresent(snapshots::add);
                    pointers.findSnapshotId(otherWorkspace).ifPresent(snapshots::add);
                } finally {
                    try {
                        endpoints.cleanup();
                    } finally {
                        try {
                            cleanupObjects(bos, objectPrefix, snapshots);
                        } finally {
                            try {
                                cleanupRedis(redis, prefix);
                            } finally {
                                if (mysql) cleanupDatabase(dataSource, owner);
                            }
                        }
                    }
                }
                System.out.println(
                        "Web snapshot live: both JVMs, real test sandboxes, snapshot objects and Redis keys"
                                + " cleaned up");
            }
        } finally {
            if (sql != null) sql.stop();
        }
    }

    private Child start(String name, Properties base) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        Path config =
                Files.createFile(
                        directory.resolve("live.yml"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Properties properties = new Properties();
        properties.putAll(base);
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        properties.setProperty("server.port", String.valueOf(port));
        properties.setProperty("horizen.agent.storage.instance-id", name);
        try (var output = Files.newOutputStream(config)) {
            new ObjectMapper(new YAMLFactory()).writeValue(output, properties);
        }
        String cp =
                Arrays.stream(
                                System.getProperty(
                                                "surefire.test.class.path",
                                                System.getProperty("java.class.path"))
                                        .split(Pattern.quote(File.pathSeparator)))
                        .filter(entry -> !entry.endsWith("test-classes"))
                        .map(entry -> Path.of(entry).toAbsolutePath().toString())
                        .collect(Collectors.joining(File.pathSeparator));
        Path log = directory.resolve("app.log");
        Process child =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-Xmx512m",
                                "-cp",
                                cp,
                                AgentWebApplication.class.getName(),
                                "--spring.config.location=" + config.toUri())
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        Child result = new Child(child, port, log);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                if (!child.isAlive())
                    throw new IllegalStateException(
                            "Web instance startup failed: " + safeLog(log, base));
                try {
                    if (get(port, "/api/status").path("ready").asBoolean()) return result;
                } catch (Exception ignored) {
                }
                Thread.sleep(200);
            }
            throw new IllegalStateException(
                    "Web instance startup timed out: " + safeLog(log, base));
        } catch (Exception error) {
            stop(result);
            throw error;
        }
    }

    private static String safeLog(Path log, Properties properties) throws IOException {
        String value = Files.readString(log);
        for (String key : properties.stringPropertyNames()) {
            if (key.contains("key")
                    || key.contains("password")
                    || key.contains("token")
                    || key.contains("redis-url")
                    || key.contains("jdbc-url")) {
                String secret = properties.getProperty(key);
                if (secret != null && !secret.isBlank())
                    value = value.replace(secret, "[redacted]");
            }
        }
        return value.substring(Math.max(0, value.length() - 6000));
    }

    private static void stop(Child child) throws InterruptedException {
        if (!child.process.isAlive()) return;
        child.process.destroy();
        if (!child.process.waitFor(20, TimeUnit.SECONDS)) {
            child.process.destroyForcibly();
            assertTrue(child.process.waitFor(5, TimeUnit.SECONDS), "Test JVM did not stop");
        }
    }

    private static Properties configuration(
            Properties e2b,
            Properties bos,
            Properties storage,
            String owner,
            String prefix,
            String objects,
            String jdbc,
            LiveEndpoints endpoints) {
        Properties p = new Properties();
        p.setProperty("server.address", "127.0.0.1");
        p.setProperty("spring.main.banner-mode", "off");
        p.setProperty("horizen.agent.api-key", "fixture-model-key");
        p.setProperty("horizen.agent.base-url", endpoints.base() + "/v1");
        p.setProperty("horizen.agent.model-name", "snapshot-fixture");
        p.setProperty("horizen.agent.model-mode", "REMOTE");
        p.setProperty("horizen.agent.stream-timeout", "5m");
        p.setProperty("horizen.agent.idle-timeout", "2m");
        p.setProperty("horizen.agent.context.compaction-enabled", "false");
        p.setProperty("horizen.agent.identity.owner-key", owner);
        p.setProperty("horizen.agent.identity.actor-id", owner);
        p.setProperty("horizen.agent.storage.mode", "DISTRIBUTED");
        p.setProperty("horizen.agent.storage.jdbc-url", jdbc);
        p.setProperty("horizen.agent.storage.jdbc-username", "sa");
        p.setProperty("horizen.agent.storage.jdbc-password", "");
        p.setProperty(
                "horizen.agent.storage.redis-url",
                required(storage, "horizen.agent.storage.redis-url"));
        p.setProperty("horizen.agent.storage.redis-key-prefix", prefix);
        p.setProperty("horizen.agent.gateway.mode", "remote");
        p.setProperty("horizen.agent.gateway.url", "");
        p.setProperty("horizen.agent.skill-release.enabled", "false");
        p.setProperty("horizen.trace.enabled", "false");
        p.setProperty("horizen.agent.artifact.bos.enabled", "false");
        p.setProperty("horizen.agent.sandbox.e2b.enabled", "true");
        p.setProperty("horizen.agent.sandbox.e2b.api-key", required(e2b, "AGENT_E2B_API_KEY"));
        p.setProperty("horizen.agent.sandbox.e2b.api-base-url", endpoints.base());
        p.setProperty(
                "horizen.agent.sandbox.e2b.runtime-base-url-pattern",
                required(e2b, "AGENT_E2B_RUNTIME_BASE_URL_PATTERN"));
        p.setProperty(
                "horizen.agent.sandbox.e2b.template-id", required(e2b, "AGENT_E2B_TEMPLATE_ID"));
        p.setProperty("horizen.agent.sandbox.e2b.workspace-root", "/tmp/horizen-web-acceptance");
        p.setProperty("horizen.agent.sandbox.e2b.isolation-scope", "SESSION");
        p.setProperty("horizen.agent.sandbox.e2b.sandbox-timeout-seconds", "300");
        p.setProperty("horizen.agent.sandbox.snapshot.bos.enabled", "true");
        for (String field : List.of("endpoint", "bucket", "access-key", "secret-key")) {
            p.setProperty(
                    "horizen.agent.sandbox.snapshot.bos." + field,
                    required(bos, "horizen.agent.artifact.bos." + field));
        }
        p.setProperty("horizen.agent.sandbox.snapshot.bos.key-prefix", objects);
        p.setProperty("horizen.agent.sandbox.snapshot.bos.max-archive-bytes", "4194304");
        return p;
    }

    private static List<JsonNode> chat(int port, String session, String message, String request)
            throws Exception {
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "sessionId",
                                session,
                                "message",
                                message,
                                "requestId",
                                request,
                                "artifactIds",
                                List.of()));
        var response =
                HTTP.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/api/chat/stream"))
                                .timeout(Duration.ofMinutes(3))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        List<JsonNode> result = new ArrayList<>();
        for (String line : response.body().lines().toList()) {
            if (line.startsWith("data:")) {
                String value = line.substring(5).strip();
                if (value.startsWith("{")) result.add(JSON.readTree(value));
            }
        }
        return result;
    }

    private static void assertReply(List<JsonNode> events, String expected) {
        assertFalse(
                events.stream().anyMatch(e -> e.path("type").asText().equals("error")),
                events.toString());
        JsonNode done =
                events.stream()
                        .filter(e -> e.path("type").asText().equals("done"))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("Missing terminal result: " + events));
        assertEquals(expected, done.path("text").asText(), events.toString());
        assertTrue(
                events.stream().anyMatch(e -> e.path("toolName").asText().equals("execute")),
                "Shell tool must execute in real E2B");
    }

    private static void assertStatus(int port, String session, String expected) throws Exception {
        assertEquals(
                expected,
                post(port, "/api/session/query", Map.of("sessionId", session))
                        .path("status")
                        .asText());
    }

    private static JsonNode post(int port, String path, Object body) throws Exception {
        var response =
                HTTP.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body());
    }

    private static JsonNode get(int port, String path) throws Exception {
        var response =
                HTTP.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException("Web probe HTTP " + response.statusCode());
        return JSON.readTree(response.body());
    }

    private static Properties load(String file) throws IOException {
        Properties p = new Properties();
        try (var input = Files.newInputStream(Path.of("..", file))) {
            p.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        return p;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing live configuration: " + key);
        return value.strip();
    }

    private static void cleanupObjects(Properties p, String prefix, Set<String> ids) {
        var config = new BosClientConfiguration();
        config.setEndpoint(required(p, "horizen.agent.artifact.bos.endpoint"));
        config.setCredentials(
                new DefaultBceCredentials(
                        required(p, "horizen.agent.artifact.bos.access-key"),
                        required(p, "horizen.agent.artifact.bos.secret-key")));
        BosClient client = new BosClient(config);
        try {
            for (String id : ids) {
                if (!id.matches("[A-Za-z0-9_-]+"))
                    throw new IllegalArgumentException("Invalid test snapshot ID");
                client.deleteObject(
                        required(p, "horizen.agent.artifact.bos.bucket"),
                        prefix + "/" + id + ".tar");
            }
        } finally {
            client.shutdown();
        }
    }

    private static void cleanupRedis(JedisPooled redis, String prefix) {
        String cursor = "0";
        do {
            var page = redis.scan(cursor, new ScanParams().match(prefix + "*").count(100));
            for (String key : page.getResult()) redis.del(key);
            cursor = page.getCursor();
        } while (!cursor.equals("0"));
    }

    private static void cleanupDatabase(DriverManagerDataSource source, String owner) {
        if (!owner.matches("web-snapshot-owner-[a-f0-9]{32}"))
            throw new IllegalArgumentException("Invalid test owner");
        var jdbc = new JdbcTemplate(source);
        for (String table :
                List.of(
                        "ha_conversation_history",
                        "ha_interaction",
                        "ha_artifact",
                        "ha_turn",
                        "ha_session")) {
            jdbc.update("DELETE FROM " + table + " WHERE owner_key = ?", owner);
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE owner_key = ?",
                            Integer.class,
                            owner));
        }
        System.out.println("Web snapshot live: MySQL rows for the test owner cleaned and verified");
    }

    private static final class Child {
        final Process process;
        final int port;
        final Path log;

        Child(Process process, int port, Path log) {
            this.process = process;
            this.port = port;
            this.log = log;
        }
    }

    /**
     * 提供合成 OpenAI SSE 回复，并通过透明代理记录真实沙箱管理调用。
     */
    private static final class LiveEndpoints implements AutoCloseable {
        final HttpServer server;
        final Properties config;
        final Set<String> created = ConcurrentHashMap.newKeySet();
        final ExecutorService workers = Executors.newFixedThreadPool(4);

        LiveEndpoints(Properties config) throws IOException {
            this.config = config;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/v1/chat/completions", this::model);
            server.createContext("/sandboxes", this::proxy);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void model(HttpExchange exchange) throws IOException {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            JsonNode messages = request.path("messages");
            int latest = -1;
            for (int i = 0; i < messages.size(); i++)
                if (messages.get(i).path("role").asText().equals("user")) latest = i;
            String mode = latest < 0 ? "" : content(messages.get(latest).path("content"));
            String result = null;
            for (int i = latest + 1; i < messages.size(); i++) {
                if (messages.get(i).path("role").asText().equals("tool"))
                    result = content(messages.get(i).path("content"));
            }
            ObjectNode delta = JSON.createObjectNode().put("role", "assistant");
            String finish;
            if (result != null) {
                delta.put(
                        "content",
                        result.contains("Exit code: 0")
                                ? "verified:" + mode
                                : "tool-failed:" + result);
                finish = "stop";
            } else {
                String python =
                        switch (mode) {
                            case "snapshot-create" ->
                                    "import pathlib; p=pathlib.Path('acceptance'); p.mkdir();"
                                            + " p.joinpath('report.md').write_text('from-a');"
                                            + " p.joinpath('binary.bin').write_bytes(bytes(range(256))*1024);"
                                            + " print('created')";
                            case "snapshot-update" ->
                                    "import pathlib; p=pathlib.Path('acceptance'); assert"
                                            + " p.joinpath('report.md').read_text()=='from-a'; assert"
                                            + " p.joinpath('binary.bin').read_bytes()==bytes(range(256))*1024;"
                                            + " p.joinpath('report.md').write_text('updated-by-b');"
                                            + " print('restored-and-updated')";
                            case "snapshot-read" ->
                                    "import pathlib; p=pathlib.Path('acceptance'); assert"
                                            + " p.joinpath('report.md').read_text()=='updated-by-b'; assert"
                                            + " p.joinpath('binary.bin').read_bytes()==bytes(range(256))*1024;"
                                            + " print('b-update-restored')";
                            case "snapshot-other-create" ->
                                    "import pathlib; p=pathlib.Path('acceptance'); p.mkdir();"
                                            + " p.joinpath('report.md').write_text('other-session-report');"
                                            + " print('other-created')";
                            case "snapshot-other-read" ->
                                    "import pathlib; assert"
                                            + " pathlib.Path('acceptance/report.md').read_text()=='other-session-report';"
                                            + " print('other-restored')";
                            default -> throw new IOException("Unexpected fixture model request");
                        };
                String command = "python3 -c '" + python.replace("'", "'\"'\"'") + "'";
                var tool =
                        delta.putArray("tool_calls")
                                .addObject()
                                .put("index", 0)
                                .put("id", "call-" + mode)
                                .put("type", "function");
                tool.putObject("function")
                        .put("name", "execute")
                        .put(
                                "arguments",
                                JSON.writeValueAsString(Map.of("command", command, "timeout", 30)));
                finish = "tool_calls";
            }
            var chunk =
                    JSON.createObjectNode()
                            .put("id", "fixture-completion")
                            .put("object", "chat.completion.chunk")
                            .put("created", 0)
                            .put("model", "snapshot-fixture");
            chunk.putArray("choices").addObject().put("index", 0).set("delta", delta);
            ((ObjectNode) chunk.path("choices").get(0)).put("finish_reason", finish);
            byte[] payload =
                    ("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        private static String content(JsonNode value) {
            if (value.isTextual()) return value.asText();
            StringBuilder result = new StringBuilder();
            for (JsonNode block : value) result.append(block.path("text").asText());
            return result.toString();
        }

        private void proxy(HttpExchange exchange) throws IOException {
            try {
                String base = required(config, "AGENT_E2B_API_BASE_URL").replaceAll("/+$", "");
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                var request =
                        HttpRequest.newBuilder(URI.create(base + exchange.getRequestURI()))
                                .timeout(Duration.ofSeconds(60))
                                .header("X-API-KEY", required(config, "AGENT_E2B_API_KEY"))
                                .header("Content-Type", "application/json")
                                .method(
                                        exchange.getRequestMethod(),
                                        HttpRequest.BodyPublishers.ofByteArray(bytes))
                                .build();
                var response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (exchange.getRequestMethod().equals("POST")
                        && exchange.getRequestURI().getPath().equals("/sandboxes")
                        && response.statusCode() < 300) {
                    String id = JSON.readTree(response.body()).path("sandboxID").asText();
                    if (!id.isBlank()) created.add(id);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(
                        response.statusCode(),
                        response.body().length == 0 ? -1 : response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            } finally {
                exchange.close();
            }
        }

        void cleanup() throws Exception {
            String base = required(config, "AGENT_E2B_API_BASE_URL").replaceAll("/+$", "");
            for (String id : created) {
                var response =
                        HTTP.send(
                                HttpRequest.newBuilder(URI.create(base + "/sandboxes/" + id))
                                        .timeout(Duration.ofSeconds(15))
                                        .header("X-API-KEY", required(config, "AGENT_E2B_API_KEY"))
                                        .DELETE()
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
                assertTrue(
                        response.statusCode() < 300
                                || response.statusCode() == 404
                                || response.statusCode() == 410,
                        "Remote test sandbox cleanup failed");
            }
        }

        @Override
        public void close() {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
