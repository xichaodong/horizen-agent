package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.storage.redis.RedisAgentRuntimeStore;
import dev.horizen.agent.web.LiveConfiguration;
import dev.horizen.agent.web.bootstrap.runtime.AgentRuntimeFactory;
import dev.horizen.agent.web.bootstrap.runtime.RuntimeAssembly;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.ContextProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.MultimodalProperties;
import dev.horizen.agent.web.config.SandboxSnapshotProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用真实模型、已配置 MySQL 和生产 RuntimeFactory，每一步创建新的会话及 Agent。
 */
@EnabledIfSystemProperty(named = "horizen.memory.quality.live", matches = "true")
class MemoryQualityConfiguredLiveTest {
    @TempDir
    Path workspace;
    private final AtomicInteger sequence = new AtomicInteger();
    private final String redisPrefix = "horizen-memory-quality:" + UUID.randomUUID() + ":";
    private JedisPooled redis;
    private JedisPool subscriptions;

    @Test
    void savesCorrectsForgetsAndRecallsOnlyConfirmedOwnerMemory() throws Exception {
        Properties model = load(".env.yml");
        Properties storage = load(".env.yml");
        var properties =
                new AgentProperties(
                        value(model, "ARK_API_KEY"),
                        value(model, "ARK_BASE_URL"),
                        value(model, "ARK_MODEL"),
                        AgentProperties.ModelMode.REMOTE,
                        6,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1));
        assertTrue(properties.ready(), "Existing model configuration is required");
        var source =
                new DriverManagerDataSource(
                        storage.getProperty("horizen.agent.storage.jdbc-url"),
                        storage.getProperty("horizen.agent.storage.jdbc-username"),
                        storage.getProperty("horizen.agent.storage.jdbc-password"));
        String owner = "memory-quality-" + UUID.randomUUID().toString().replace("-", "");
        String other = owner + "-other";
        var documents =
                new JdbcWorkspaceDocumentRepository(
                        source, InMemoryWorkspaceContentRepository.shared(source));
        Files.writeString(workspace.resolve("AGENTS.md"), "你是通用助手。基于确认的信息回答，未知信息不要编造。\n");
        URI redisUri =
                URI.create(System.getProperty("horizen.redis.url", "redis://127.0.0.1:6379"));
        redis = new JedisPooled(redisUri);
        subscriptions = new JedisPool(redisUri);
        try {
            var saved =
                    run(
                            properties,
                            documents,
                            owner,
                            "请长期记住：我的验收专用报告代号是 marigold-42。" + "这个代号以后跨会话都有效。请保存并简短确认。");
            assertTool(saved, "memory_save");
            assertTrue(current(documents, owner).contains("marigold-42"));
            System.out.println("Memory quality live: explicit save passed");

            assertRecall(
                    run(
                            properties,
                            new JdbcWorkspaceDocumentRepository(
                                    source, InMemoryWorkspaceContentRepository.shared(source)),
                            owner,
                            recallPrompt()),
                    "marigold-42");
            assertRecall(run(properties, documents, other, recallPrompt()), null);
            assertTrue(current(documents, other).isEmpty());
            System.out.println(
                    "Memory quality live: fresh-session recall and owner isolation passed");

            var corrected =
                    run(
                            properties,
                            documents,
                            owner,
                            "纠正长期记忆：我的验收专用报告代号改为 saffron-73。" + "请修改原条目，保留唯一有效值，不要另存互相冲突的记忆。");
            assertTool(corrected, "memory_manage");
            assertTrue(current(documents, owner).contains("saffron-73"));
            assertFalse(current(documents, owner).contains("marigold-42"));
            assertRecall(
                    run(
                            properties,
                            new JdbcWorkspaceDocumentRepository(
                                    source, InMemoryWorkspaceContentRepository.shared(source)),
                            owner,
                            recallPrompt()),
                    "saffron-73");
            System.out.println("Memory quality live: correction and subsequent recall passed");

            var forgotten =
                    run(
                            properties,
                            documents,
                            owner,
                            "请忘记我的验收专用报告代号，删除对应长期记忆条目。" + "不要删除其他内容，完成后简短确认。");
            assertTool(forgotten, "memory_manage");
            assertFalse(current(documents, owner).contains("saffron-73"));
            assertRecall(
                    run(
                            properties,
                            new JdbcWorkspaceDocumentRepository(
                                    source, InMemoryWorkspaceContentRepository.shared(source)),
                            owner,
                            recallPrompt()),
                    null);
            assertTrue(
                    documents
                            .list(owner, AgentRuntimeFactory.AGENT_KEY, "global", "memory/", 10, 0)
                            .stream()
                            .anyMatch(document -> document.getContent().contains("marigold-42")));
            System.out.println(
                    "Memory quality live: forget does not revive the audited value passed");

            var temporary =
                    run(
                            properties,
                            documents,
                            owner,
                            "下面是临时调试讨论：本次调用超时，结果未知。" + "我猜下一次测试代号可能是 lilac-99，但尚未确认，现在只讨论可能原因。");
            assertTrue(
                    temporary.stream()
                            .noneMatch(
                                    e ->
                                            e.getType() == AgentRuntimeEvent.Type.TOOL_STARTED
                                                    && ("memory_save".equals(e.getToolName())
                                                    || "memory_manage"
                                                    .equals(e.getToolName()))));
            assertFalse(current(documents, owner).contains("lilac-99"));
            System.out.println(
                    "Memory quality live: temporary guesses and unknown outcomes were not persisted");
        } finally {
            var jdbc = new JdbcTemplate(source);
            for (String table : List.of("ha_workspace_operation", "ha_workspace_file")) {
                jdbc.update("DELETE FROM " + table + " WHERE owner_key IN (?,?)", owner, other);
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM " + table + " WHERE owner_key IN (?,?)",
                                Integer.class,
                                owner,
                                other));
            }
            try {
                Set<String> keys = redis.keys(redisPrefix + "*");
                if (!keys.isEmpty()) redis.del(keys.toArray(String[]::new));
            } finally {
                redis.close();
                subscriptions.close();
            }
            System.out.println("Memory quality live: synthetic owner rows cleaned and verified");
        }
    }

    private List<AgentRuntimeEvent> run(
            AgentProperties properties,
            JdbcWorkspaceDocumentRepository documents,
            String owner,
            String message) {
        var context = new ContextProperties();
        context.setCompactionEnabled(false);
        var state =
                new RedisAgentRuntimeStore(
                        redis, subscriptions, redisPrefix, Duration.ofMinutes(10));
        var gateway =
                new GatewayProperties(
                        "", "", null, Set.of(), GatewayProperties.Mode.REMOTE, null, Map.of());
        var sandbox =
                new E2bSandboxProperties(false, "", "", "", "", "", null, null, null, null, null);
        var multimodal = new MultimodalProperties(null, null, null, null);
        try (var runtime =
                     AgentRuntimeFactory.create(
                             RuntimeAssembly.builder()
                                     .properties(properties)
                                     .contextProperties(context)
                                     .gatewayProperties(gateway)
                                     .sandboxProperties(sandbox)
                                     .multimodalProperties(multimodal)
                                     .traceConfig(null)
                                     .horizenExporter(null)
                                     .skillRepository(null)
                                     .distributedStore(state)
                                     .sessionTurns(null)
                                     .artifactSupport(null)
                                     .askUsers(null)
                                     .workspaceDocuments(documents)
                                     .snapshots(null)
                                     .snapshotProperties(new SandboxSnapshotProperties())
                                     .snapshotPointers(null)
                                     .publishedWorkspace(workspace)
                                     .infrastructure(null)
                                     .build())) {
            var events =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .ownerKey(owner)
                                            .sessionId(
                                                    "memory-quality-session-"
                                                            + sequence.incrementAndGet())
                                            .turnId(UUID.randomUUID().toString())
                                            .message(message)
                                            .build())
                            .collectList()
                            .block(Duration.ofSeconds(90));
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED),
                    () ->
                            "Unexpected terminal state: "
                                    + events.stream().map(AgentRuntimeEvent::getType).toList());
            return events;
        }
    }

    private static void assertTool(List<AgentRuntimeEvent> events, String tool) {
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.getType() == AgentRuntimeEvent.Type.TOOL_STARTED
                                                && tool.equals(e.getToolName())),
                "Expected actual invocation of " + tool);
    }

    private static String current(JdbcWorkspaceDocumentRepository documents, String owner) {
        return documents
                .find(
                        new WorkspaceDocumentKey(
                                owner, AgentRuntimeFactory.AGENT_KEY, "global", "MEMORY.md"))
                .map(document -> document.getContent())
                .orElse("");
    }

    private static String recallPrompt() {
        return "我长期保存的验收专用报告代号是什么？只输出JSON：{\"known\":true或false,\"value\":代号或null}。"
                + "没有有效记忆时如实返回known=false和value=null，不要猜测。";
    }

    private static void assertRecall(List<AgentRuntimeEvent> events, String expected)
            throws Exception {
        String text =
                events.stream()
                        .filter(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED)
                        .findFirst()
                        .orElseThrow()
                        .getText();
        var answer =
                new ObjectMapper()
                        .readTree(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
        assertTrue(answer.path("known").isBoolean());
        assertEquals(expected != null, answer.path("known").asBoolean());
        if (expected == null) assertTrue(answer.path("value").isNull());
        else assertEquals(expected, answer.path("value").asText());
    }

    private static Properties load(String name) throws Exception {
        var result = new Properties();
        try (var input = Files.newInputStream(Path.of("..", name))) {
            result.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        return result;
    }

    private static String value(Properties local, String key) {
        return LiveConfiguration.value(local, key);
    }
}
