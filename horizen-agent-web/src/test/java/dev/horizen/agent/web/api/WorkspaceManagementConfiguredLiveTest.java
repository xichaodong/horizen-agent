package dev.horizen.agent.web.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import dev.horizen.agent.adapter.workspace.horizen.HorizenAgentReleaseRepository;
import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.application.workspace.WorkspaceManagementService;
import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.storage.bos.BosArtifactContentStore;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.web.AgentWebApplication;
import dev.horizen.agent.web.LiveConfiguration;
import dev.horizen.agent.web.config.WorkspaceStorageProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import redis.clients.jedis.JedisPooled;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.net.ServerSocket;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 显式本地验收，使用独立 Agent 表结构、真实 BOS、实际 Server 和 Vite 代理。 */
@EnabledIfSystemProperty(named = "horizen.workspace.management.live", matches = "true")
class WorkspaceManagementConfiguredLiveTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void webServerAgentChainPersistsOnlyInAgentDatabase() throws Exception {
        boolean execute = Boolean.getBoolean("horizen.workspace.execution.live");
        String redisUrl = System.getProperty("horizen.redis.url", "redis://127.0.0.1:16479");
        if (execute)
            try (var redis = new JedisPooled(URI.create(redisUrl))) {
                String version =
                        redis.info("server")
                                .lines()
                                .filter(line -> line.startsWith("redis_version:"))
                                .findFirst()
                                .orElse("unknown");
                assertTrue(
                        version.startsWith("redis_version:4.0."),
                        "Real workspace acceptance requires Redis 4.0; actual " + version);
                System.out.println("Real workspace acceptance using " + version);
            }
        Path serverRoot = Path.of(System.getProperty("horizen.server.root"));
        Path webRoot = Path.of(System.getProperty("horizen.web.root"));
        Properties storage = load(Path.of("../.env.yml")), bos = load(Path.of("../.env.yml"));
        URI databaseUri =
                URI.create(storage.getProperty("horizen.agent.storage.jdbc-url").substring(5));
        assertTrue(
                Set.of("127.0.0.1", "localhost").contains(databaseUri.getHost()),
                "acceptance only operates on local MySQL");
        String database =
                "ha_workspace_acceptance_" + UUID.randomUUID().toString().replace("-", "");
        String base = "jdbc:mysql://" + databaseUri.getRawAuthority() + "/";
        String jdbcSuffix =
                databaseUri.getRawQuery() == null ? "" : "?" + databaseUri.getRawQuery();
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                base + jdbcSuffix,
                                storage.getProperty("horizen.agent.storage.jdbc-username"),
                                storage.getProperty("horizen.agent.storage.jdbc-password")));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        var source =
                new DriverManagerDataSource(
                        base + database + jdbcSuffix,
                        storage.getProperty("horizen.agent.storage.jdbc-username"),
                        storage.getProperty("horizen.agent.storage.jdbc-password"));
        var jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        String token = UUID.randomUUID().toString();
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "--horizen.local-config=",
                                "--server.port=0",
                                "--horizen.agent.api-key=",
                                "--horizen.agent.model-mode=REMOTE",
                                "--horizen.agent.artifact.bos.enabled=false",
                                "--horizen.agent.workspace-release.enabled=false",
                                "--horizen.agent.storage.mode=DISTRIBUTED",
                                "--horizen.agent.storage.redis-url=redis://127.0.0.1:1",
                                "--horizen.agent.storage.jdbc-url=" + base + database + jdbcSuffix,
                                "--horizen.agent.storage.jdbc-username="
                                        + storage.getProperty(
                                                "horizen.agent.storage.jdbc-username"),
                                "--horizen.agent.storage.jdbc-password="
                                        + storage.getProperty(
                                                "horizen.agent.storage.jdbc-password"),
                                "--horizen.agent.workspace-management.enabled=true",
                                "--horizen.agent.workspace-management.token=" + token,
                                "--horizen.agent.workspace-management.project-ids=7",
                                "--horizen.agent.workspace-storage.key-prefix="
                                        + bos.getProperty("horizen.agent.artifact.bos.key-prefix")
                                        + "/management-live/"
                                        + UUID.randomUUID()));
        for (String field : List.of("endpoint", "bucket", "access-key", "secret-key"))
            args.add(
                    "--horizen.agent.workspace-storage."
                            + field
                            + "="
                            + bos.getProperty("horizen.agent.artifact.bos." + field));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String owner = "workspace-live-" + suffix;
        String redisPrefix = "workspace-execution:" + suffix + ":";
        Path runtimeCache = Files.createTempDirectory("workspace-execution-cache-");
        List<String> snapshotIds = new ArrayList<>();
        if (execute) {
            Properties model = load(Path.of("../.env.yml")), sandbox = load(Path.of("../.env.yml"));
            assertFalse(
                    model.getProperty("ARK_API_KEY", "").isBlank(),
                    "model credentials must already be configured");
            args.remove("--horizen.agent.api-key=");
            args.remove("--horizen.agent.workspace-release.enabled=false");
            args.remove("--horizen.agent.storage.redis-url=redis://127.0.0.1:1");
            args.addAll(
                    List.of(
                            "--horizen.agent.api-key=" + model.getProperty("ARK_API_KEY"),
                            "--horizen.agent.max-iters=12",
                            "--horizen.agent.stream-timeout=180s",
                            "--horizen.agent.idle-timeout=120s",
                            "--horizen.agent.workspace-release.enabled=true",
                            "--horizen.agent.workspace-release.source=LOCAL",
                            "--horizen.agent.workspace-release.project-id=7",
                            "--horizen.agent.workspace-release.cache-directory=" + runtimeCache,
                            "--horizen.agent.storage.redis-url=" + redisUrl,
                            "--horizen.agent.storage.redis-key-prefix=" + redisPrefix,
                            "--horizen.agent.identity.owner-key=" + owner,
                            "--horizen.agent.identity.actor-id=acceptance",
                            "--horizen.agent.gateway.mode=REMOTE",
                            "--horizen.agent.gateway.url=",
                            "--horizen.agent.gateway.token=",
                            "--horizen.trace.enabled=false",
                            "--horizen.agent.sandbox.e2b.enabled=true",
                            "--horizen.agent.sandbox.e2b.isolation-scope=SESSION"));
            for (String field :
                    List.of(
                            "api-key",
                            "api-base-url",
                            "runtime-base-url-pattern",
                            "template-id",
                            "workspace-root"))
                args.add(
                        "--horizen.agent.sandbox.e2b."
                                + field
                                + "="
                                + sandbox.getProperty(
                                        "AGENT_E2B_"
                                                + field.toUpperCase(Locale.ROOT)
                                                        .replace('-', '_')));
            if (model.containsKey("ARK_BASE_URL"))
                args.add("--horizen.agent.base-url=" + model.getProperty("ARK_BASE_URL"));
            if (model.containsKey("ARK_MODEL"))
                args.add("--horizen.agent.model-name=" + model.getProperty("ARK_MODEL"));
        }
        Process server = null, web = null;
        ServletWebServerApplicationContext context = null;
        List<String> references = new ArrayList<>();
        Path serverLog = Files.createTempFile("workspace-server-live-", ".log"),
                webLog = Files.createTempFile("workspace-web-live-", ".log");
        String key =
                execute
                        ? "horizen-web-agent"
                        : "workspace-live-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            context =
                    (ServletWebServerApplicationContext)
                            SpringApplication.run(
                                    AgentWebApplication.class, args.toArray(String[]::new));
            int agentPort = context.getWebServer().getPort();
            int serverPort = freePort(), webPort = freePort();
            var serverCommand =
                    new ProcessBuilder(
                            "java",
                            "-Xmx512m",
                            "-jar",
                            "horizen-app/target/horizen-app.jar",
                            "--spring.profiles.active=local",
                            "--server.port=" + serverPort,
                            "--horizen.agent-workspace.management-url=http://127.0.0.1:"
                                    + agentPort,
                            "--horizen.agent-workspace.management-token=" + token,
                            "--horizen.remote-evaluation.enabled=false",
                            "--horizen.reclaim.initial-delay-ms=3600000",
                            "--horizen.rag.auto-sync.enabled=false");
            serverCommand.directory(serverRoot.toFile());
            serverCommand.environment().put("EM_PRODUCT_LINE", "horizen-local");
            serverCommand.environment().put("EM_APP", "horizen-server");
            serverCommand.redirectErrorStream(true).redirectOutput(serverLog.toFile());
            server = serverCommand.start();
            await("http://127.0.0.1:" + serverPort + "/actuator/health", server);
            var webCommand =
                    new ProcessBuilder(
                            "npm",
                            "run",
                            "dev",
                            "--",
                            "--host",
                            "127.0.0.1",
                            "--port",
                            String.valueOf(webPort),
                            "--strictPort");
            webCommand.directory(webRoot.toFile());
            webCommand
                    .environment()
                    .put("VITE_HORIZEN_API_TARGET", "http://127.0.0.1:" + serverPort);
            webCommand.environment().put("VITE_HORIZEN_API_BASE_URL", "");
            webCommand.redirectErrorStream(true).redirectOutput(webLog.toFile());
            web = webCommand.start();
            await("http://127.0.0.1:" + webPort + "/", web);
            String url = "http://127.0.0.1:" + webPort + "/horizen/api/workspaces/v1/" + key;
            String oldUrl =
                    "http://127.0.0.1:" + webPort + "/horizen/api/agent-workspaces/v1/" + key;
            JsonNode oldDraft = request("GET", oldUrl + "/draft", null);
            assertEquals(200, oldDraft.path("code").asInt());
            String oldSkillsUrl = "http://127.0.0.1:" + webPort + "/horizen/api/skills/v1/skills";
            JsonNode oldSkills = request("GET", oldSkillsUrl, null);
            assertEquals(200, oldSkills.path("code").asInt());
            assertEquals(
                    0, request("GET", url + "/draft", null).path("data").path("version").asInt());
            JsonNode first =
                    request(
                            "PUT",
                            url + "/draft",
                            Map.of(
                                    "version",
                                    0,
                                    "skills",
                                    List.of(),
                                    "assets",
                                    List.of(
                                            Map.of(
                                                    "path",
                                                    "AGENTS.md",
                                                    "content",
                                                    "Synthetic policy v1"),
                                            Map.of(
                                                    "path",
                                                    "knowledge/check.md",
                                                    "content",
                                                    "Synthetic knowledge"))));
            assertEquals(200, first.path("code").asInt());
            assertEquals(1, first.path("data").path("version").asInt());
            references.addAll(
                    jdbc.queryForList("SELECT content_ref FROM ha_workspace_file", String.class));
            assertEquals(
                    409,
                    request(
                                    "PUT",
                                    url + "/draft",
                                    Map.of("version", 0, "skills", List.of(), "assets", List.of()))
                            .path("code")
                            .asInt());
            byte[] template = new byte[] {0, 1, 2, (byte) 255};
            String script =
                    "from pathlib import Path\n"
                            + "import hashlib\n"
                            + "root=Path(__file__).resolve().parent.parent\n"
                            + "rule=(root/'references/rules.md').read_text().strip()\n"
                            + "checksum=hashlib.sha256((root/'assets/template.xlsx').read_bytes()).hexdigest()\n"
                            + "result=rule+'|42|'+checksum\n"
                            + "out=Path('files/probe-result.txt')\n"
                            + "out.parent.mkdir(parents=True,exist_ok=True)\n"
                            + "out.write_text(result+'\\n"
                            + "')\n"
                            + "print(result)\n";
            String rule = "Synthetic rule " + suffix;
            JsonNode folder =
                    uploadDirectory(
                            url + "/folder",
                            1,
                            Map.of(
                                    "example/SKILL.md",
                                    "---\nname: example\ndescription: 合成工作区验收，用脚本计算结果并检查模板。\n---\n先用 load_skill_through_path 读取 references/rules.md 和 scripts/run.py，然后使用 execute 运行此 Skill 文件目录下的 scripts/run.py，不要重写脚本。该脚本读取 assets/template.xlsx 并将结果写到当前工作区 files/probe-result.txt。用户要求保存长期记忆时调用 memory_save。\n"
                                            .getBytes(StandardCharsets.UTF_8),
                                    "example/scripts/run.py",
                                    script.getBytes(StandardCharsets.UTF_8),
                                    "example/references/rules.md",
                                    rule.getBytes(StandardCharsets.UTF_8),
                                    "example/assets/template.xlsx",
                                    template));
            assertEquals(200, folder.path("code").asInt());
            assertEquals(2, folder.path("data").path("version").asInt());
            JsonNode published =
                    request(
                            "POST",
                            url + "/releases",
                            Map.of("expectedVersion", 2, "notes", "Synthetic live acceptance"));
            assertEquals(200, published.path("code").asInt());
            assertEquals(
                    6, request("GET", url + "/draft", null).path("data").path("assets").size());
            assertEquals(
                    1,
                    request("GET", url + "/releases/current", null)
                            .path("data")
                            .path("releaseNo")
                            .asInt());
            assertEquals(
                    6,
                    jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_file", Integer.class));
            assertEquals(
                    12,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ha_workspace_operation", Integer.class));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                                    + " table_name='ha_workspace' AND column_name='skill_selection_json'",
                            Integer.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ha_workspace_release", Integer.class));
            JsonNode audit = request("GET", url + "/audit?path=AGENTS.md", null);
            assertEquals(200, audit.path("code").asInt());
            assertEquals(2, audit.path("data").path("items").size());
            long created = audit.path("data").path("items").get(1).path("sequence").asLong();
            JsonNode change = request("GET", url + "/audit/" + created + "?path=AGENTS.md", null);
            assertEquals(
                    "Synthetic policy v1",
                    change.path("data").path("after").path("content").asText());
            JsonNode exports =
                    request(
                            "GET",
                            url
                                    + "/releases/"
                                    + published.path("data").path("id").asLong()
                                    + "/exports",
                            null);
            assertEquals(200, exports.path("code").asInt());
            assertEquals(1, exports.path("data").size());
            String packageUrl = exports.path("data").get(0).path("url").asText();
            byte[] archive =
                    http.send(
                                    HttpRequest.newBuilder(URI.create(packageUrl))
                                            .timeout(Duration.ofSeconds(30))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofByteArray())
                            .body();
            assertEquals(
                    exports.path("data").get(0).path("sha256").asText(),
                    WorkspaceManagementService.hash(archive));
            Map<String, byte[]> exported = new HashMap<>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null)
                    exported.put(entry.getName(), zip.readAllBytes());
            }
            assertEquals(
                    Set.of(
                            "SKILL.md",
                            "scripts/run.py",
                            "references/rules.md",
                            "assets/template.xlsx"),
                    exported.keySet());
            assertArrayEquals(template, exported.get("assets/template.xlsx"));
            String memoryOwner = "synthetic-memory-owner";
            jdbc.update(
                    "INSERT INTO"
                            + " ha_session(owner_key,session_id,status,created_by,title,last_message_at,created_at,updated_at,project_id,agent_key)"
                            + " VALUES(?,'audit-session','IDLE',?,'synthetic',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,7,?)",
                    memoryOwner,
                    memoryOwner,
                    key);
            var contents = context.getBean(WorkspaceContentRepository.class);
            var memories =
                    new CloudMemoryService(new JdbcWorkspaceDocumentRepository(source, contents));
            memories.save(
                    memoryOwner,
                    key,
                    "remember",
                    "first fact",
                    "audit-session",
                    "turn-one",
                    "tool-one");
            memories.replaceExactLine(
                    memoryOwner,
                    key,
                    "revise",
                    "first fact",
                    "second fact",
                    "audit-session",
                    "turn-two",
                    "tool-two");
            assertTrue(
                    request("GET", url + "/memory?ownerKey=" + memoryOwner, null)
                            .path("data")
                            .path("content")
                            .asText()
                            .contains("second fact"));
            JsonNode memoryHistory =
                    request("GET", url + "/audit?path=MEMORY.md&ownerKey=" + memoryOwner, null);
            assertEquals(2, memoryHistory.path("data").path("items").size());
            long memoryChange =
                    memoryHistory.path("data").path("items").get(0).path("sequence").asLong();
            JsonNode difference =
                    request(
                            "GET",
                            url
                                    + "/audit/"
                                    + memoryChange
                                    + "?path=MEMORY.md&ownerKey="
                                    + memoryOwner,
                            null);
            assertTrue(
                    difference
                            .path("data")
                            .path("before")
                            .path("content")
                            .asText()
                            .contains("first fact"));
            assertTrue(
                    difference
                            .path("data")
                            .path("after")
                            .path("content")
                            .asText()
                            .contains("second fact"));
            assertEquals(
                    403,
                    request("GET", url + "/memory?ownerKey=another-user", null)
                            .path("code")
                            .asInt());
            if (execute) {
                assertTrue(
                        request("GET", "http://127.0.0.1:" + agentPort + "/api/status", null)
                                .path("ready")
                                .asBoolean());
                String session = "execution-" + suffix;
                String marker = "probe-memory-" + suffix;
                var firstEvents =
                        executeAgent(
                                agentPort,
                                session,
                                "这是合成工作区验收，请使用 example Skill 读取参考资料并实际运行其 scripts/run.py，不要重写脚本。它会读取二进制模板并把结果写入"
                                        + " files/probe-result.txt。另请长期记住我的验收代号为 "
                                        + marker
                                        + "，调用 memory_save 保存。最后输出脚本执行结果。不要使用任何业务工具。",
                                owner);
                assertTrue(
                        firstEvents.stream()
                                .anyMatch(
                                        e ->
                                                "load_skill_through_path"
                                                        .equals(e.path("toolName").asText())),
                        "Skill must actually load progressively");
                assertTrue(
                        firstEvents.stream()
                                .anyMatch(e -> "execute".equals(e.path("toolName").asText())),
                        "script must execute in sandbox");
                assertTrue(
                        firstEvents.stream()
                                .anyMatch(e -> "memory_save".equals(e.path("toolName").asText())),
                        "memory tool must actually run");
                assertTrue(
                        request("GET", url + "/memory?ownerKey=" + owner, null)
                                .path("data")
                                .path("content")
                                .asText()
                                .contains(marker));
                snapshotIds.addAll(
                        jdbc.queryForList(
                                "SELECT snapshot_id FROM ha_session WHERE snapshot_id IS NOT NULL",
                                String.class));
                var secondEvents =
                        executeAgent(
                                agentPort,
                                session,
                                "请只读取上一轮已保存的 files/probe-result.txt，返回里面的完整内容。不要重新生成文件，不要运行 Python 脚本。",
                                owner);
                String answer =
                        secondEvents.stream()
                                .filter(e -> "done".equals(e.path("type").asText()))
                                .map(e -> e.path("text").asText())
                                .findFirst()
                                .orElse("");
                assertTrue(
                        answer.contains(rule + "|42|" + WorkspaceManagementService.hash(template)),
                        "second Turn must recover the actual file and binary-derived checksum");
                snapshotIds.addAll(
                        jdbc.queryForList(
                                "SELECT snapshot_id FROM ha_session WHERE snapshot_id IS NOT NULL",
                                String.class));
                var thirdEvents =
                        executeAgent(
                                agentPort,
                                "fresh-" + suffix,
                                "我长期保存的验收代号是什么？只返回代号，不要猜测或修改记忆。",
                                owner);
                assertTrue(
                        thirdEvents.stream()
                                .filter(e -> "done".equals(e.path("type").asText()))
                                .anyMatch(e -> e.path("text").asText().contains(marker)),
                        "fresh Session must recall persisted memory");
                assertFalse(
                        request("GET", url + "/audit?path=MEMORY.md&ownerKey=" + owner, null)
                                .path("data")
                                .path("items")
                                .isEmpty());
                System.out.println(
                        "Real LOCAL runtime acceptance: progressive Skill load -> sandbox script/template ->"
                                + " BOS Session snapshot -> second Turn file recovery -> new Session memory recall"
                                + " passed");
            }
            assertEquals(
                    oldDraft.path("data"), request("GET", oldUrl + "/draft", null).path("data"));
            assertEquals(oldSkills.path("data"), request("GET", oldSkillsUrl, null).path("data"));
            System.out.println(
                    "Workspace local acceptance passed: Web -> Server -> Agent -> MySQL/BOS; file history,"
                            + " full memory before/after, project isolation and immutable publication passed");
        } finally {
            if (web != null) {
                web.descendants().forEach(ProcessHandle::destroy);
                web.destroy();
                web.waitFor(5, TimeUnit.SECONDS);
            }
            if (server != null) {
                server.destroy();
                server.waitFor(10, TimeUnit.SECONDS);
            }
            if (context != null) {
                var contents = context.getBean(WorkspaceContentRepository.class);
                references.addAll(
                        jdbc.queryForList(
                                "SELECT content_ref FROM ha_workspace_file", String.class));
                references.addAll(
                        jdbc.queryForList(
                                "SELECT before_ref FROM ha_workspace_operation WHERE before_ref IS NOT NULL",
                                String.class));
                references.addAll(
                        jdbc.queryForList(
                                "SELECT after_ref FROM ha_workspace_operation WHERE after_ref IS NOT NULL",
                                String.class));
                for (String manifest :
                        jdbc.queryForList(
                                "SELECT manifest_json FROM ha_workspace_release", String.class))
                    for (var archive : json.readTree(manifest).path("archives"))
                        references.add(archive.path("reference").asText());
                new HashSet<>(references).forEach(contents::delete);
                if (execute) {
                    snapshotIds.addAll(
                            jdbc.queryForList(
                                    "SELECT snapshot_id FROM ha_session WHERE snapshot_id IS NOT NULL",
                                    String.class));
                    var config =
                            context.getBean(WorkspaceStorageProperties.class)
                                    .directoryConfig(64L * 1024 * 1024);
                    var cleanup = new BosArtifactContentStore(config);
                    for (String snapshot : new HashSet<>(snapshotIds))
                        cleanup.delete(config.getKeyPrefix() + "/" + snapshot + ".tar");
                    try (var redis = new JedisPooled(URI.create(redisUrl))) {
                        Set<String> keys = redis.keys(redisPrefix + "*");
                        if (!keys.isEmpty()) redis.del(keys.toArray(String[]::new));
                    }
                }
                context.close();
            }
            HorizenAgentReleaseRepository.delete(runtimeCache);
            admin.execute("DROP DATABASE " + database);
            System.out.println(
                    "Workspace acceptance processes stopped; temporary Agent schema and BOS test objects"
                            + " cleaned. Logs: "
                            + serverLog
                            + ", "
                            + webLog);
        }
    }

    private JsonNode request(String method, String url, Object body) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(40))
                        .header("X-Project-Id", "7")
                        .header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        return json.readTree(
                http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private List<JsonNode> executeAgent(int port, String session, String message, String owner)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat/stream"))
                        .timeout(Duration.ofSeconds(185))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofByteArray(
                                        json.writeValueAsBytes(
                                                Map.of(
                                                        "sessionId",
                                                        session,
                                                        "requestId",
                                                        UUID.randomUUID().toString(),
                                                        "message",
                                                        message,
                                                        "artifactIds",
                                                        List.of()))))
                        .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        List<JsonNode> events = new ArrayList<>();
        try (var reader =
                new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; )
                if (line.startsWith("data:") && !line.substring(5).isBlank()) {
                    JsonNode event = json.readTree(line.substring(5).trim());
                    events.add(event);
                    if (Set.of("tool_start", "tool_end", "error", "done", "approval_required")
                            .contains(event.path("type").asText()))
                        System.out.println(
                                "LOCAL execution "
                                        + event.path("type").asText()
                                        + " "
                                        + event.path("toolName").asText()
                                        + " "
                                        + event.path("status").asText());
                }
        }
        assertTrue(
                events.stream().anyMatch(e -> "done".equals(e.path("type").asText())),
                () ->
                        "Agent did not complete: "
                                + events.stream()
                                        .filter(
                                                e ->
                                                        Set.of("error", "approval_required")
                                                                .contains(e.path("type").asText()))
                                        .map(e -> e.toString())
                                        .toList());
        return events;
    }

    private JsonNode uploadDirectory(String url, long version, Map<String, byte[]> files)
            throws Exception {
        String boundary = "workspace-" + UUID.randomUUID();
        var output = new ByteArrayOutputStream();
        BiConsumer<String, String> field =
                (name, value) -> {
                    try {
                        output.write(
                                ("--"
                                                + boundary
                                                + "\r\nContent-Disposition: form-data; name=\""
                                                + name
                                                + "\"\r\n\r\n"
                                                + value
                                                + "\r\n")
                                        .getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                };
        field.accept("expectedVersion", String.valueOf(version));
        field.accept("prefix", "skills/");
        for (var file : files.entrySet()) {
            field.accept("paths", file.getKey());
            output.write(
                    ("--"
                                    + boundary
                                    + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                                    + file.getKey().substring(file.getKey().lastIndexOf('/') + 1)
                                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            output.write(file.getValue());
            output.write("\r\n".getBytes());
        }
        output.write(("--" + boundary + "--\r\n").getBytes());
        var request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(45))
                        .header("X-Project-Id", "7")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()))
                        .build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private void await(String url, Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException("Acceptance process exited");
            try {
                if (http.send(
                                        HttpRequest.newBuilder(URI.create(url))
                                                .timeout(Duration.ofSeconds(1))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode()
                        == 200) return;
            } catch (IOException ignored) {
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Acceptance service startup timed out");
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Properties load(Path path) throws Exception {
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        return properties;
    }
}
