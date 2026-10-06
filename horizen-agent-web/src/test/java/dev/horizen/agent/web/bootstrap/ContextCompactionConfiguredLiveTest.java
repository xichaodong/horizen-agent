package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.context.ConfiguredCompactionModel;
import dev.horizen.agent.context.ContextCompactionTelemetry;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.storage.redis.RedisAgentRuntimeStore;
import dev.horizen.agent.web.LiveConfiguration;
import dev.horizen.agent.web.bootstrap.model.AgentModelFactory;
import dev.horizen.agent.web.bootstrap.runtime.ContextRuntimeConfigurer;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.ContextProperties;

import io.agentscope.core.message.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * 显式启用且仅使用合成事实的验收，验证真实摘要和回复，以及新 Agent 共享 Redis 状态。
 */
@EnabledIfSystemProperty(named = "horizen.context.compaction.live", matches = "true")
class ContextCompactionConfiguredLiveTest {
    @TempDir
    Path workspace;

    @Test
    void preservesDecisionsAndUnknownWriteOutcomeAcrossCompactionAndNewInstance() throws Exception {
        Properties local = new Properties();
        Path file = Path.of("../.env.yml");
        if (Files.exists(file))
            try (var input = Files.newInputStream(file)) {
                local.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
            }
        var properties =
                new AgentProperties(
                        value(local, "ARK_API_KEY"),
                        value(local, "ARK_BASE_URL"),
                        value(local, "ARK_MODEL"),
                        AgentProperties.ModelMode.REMOTE,
                        2,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1));
        assertTrue(
                properties.ready(),
                "Configure the existing model credentials before running this opt-in test");
        var context = new ContextProperties();
        context.setCompressionModelName(value(local, "AGENT_COMPRESSION_MODEL"));
        context.setCompressionMaxOutputTokens(8_192);
        context.setCompressionTimeout(Duration.ofSeconds(45));
        context.setCompressionMaxAttempts(1);
        context.setTriggerMessages(0);
        context.setTriggerTokens(3_000);
        context.setKeepTokens(0);
        context.setKeepMessages(2);
        context.setAbortOnSummaryFailure(true);
        String owner = "context-owner-" + UUID.randomUUID();
        String prefix = "horizen-context-acceptance:" + UUID.randomUUID() + ":";
        URI uri = URI.create(System.getProperty("horizen.redis.url", "redis://127.0.0.1:6379"));
        try (var redis = new JedisPooled(uri);
             var subscriptions = new JedisPool(uri)) {
            assertTrue(
                    redis.info("server").contains("redis_version:4.0."),
                    "This acceptance run requires Redis 4.0");
            try {
                var storeA =
                        new RedisAgentRuntimeStore(
                                redis, subscriptions, prefix, Duration.ofMinutes(10))
                                .agentStateStore();
                List<Msg> history = history();
                storeA.save(
                        owner,
                        "session",
                        "agent_state",
                        AgentState.builder()
                                .userId(owner)
                                .sessionId("session")
                                .context(history)
                                .build());
                try (var runtime = runtime(properties, context, storeA)) {
                    assertAnswer(call(runtime, owner));
                }
                AgentState saved =
                        storeA.get(owner, "session", "agent_state", AgentState.class).orElseThrow();
                for (int i = 0; i < 3; i++) {
                    assertEquals(history.get(i).getId(), saved.getContext().get(i).getId());
                    assertEquals(history.get(i).getRole(), saved.getContext().get(i).getRole());
                    assertEquals(
                            history.get(i).getTextContent(),
                            saved.getContext().get(i).getTextContent());
                }
                assertTrue(
                        saved.getContext().stream()
                                .anyMatch(
                                        m ->
                                                ConversationCompactor.SUMMARY_MSG_NAME.equals(
                                                        m.getName())));
                assertTrue(saved.getContext().size() < history.size());
                // 使用新的存储实例和 Harness 从 Redis 加载压缩后的状态，
                // 再基于较小的状态执行一轮压缩，验证连续摘要。
                context.setTriggerMessages(4);
                var storeB =
                        new RedisAgentRuntimeStore(
                                redis, subscriptions, prefix, Duration.ofMinutes(10))
                                .agentStateStore();
                try (var runtime = runtime(properties, context, storeB)) {
                    assertAnswer(call(runtime, owner));
                }
                assertTrue(
                        storeB.get("other-owner", "session", "agent_state", AgentState.class)
                                .isEmpty());
            } finally {
                Set<String> keys = redis.keys(prefix + "*");
                if (!keys.isEmpty()) redis.del(keys.toArray(String[]::new));
            }
        }
    }

    private HarnessAgentRuntime runtime(
            AgentProperties properties, ContextProperties context, AgentStateStore store) {
        var primary = AgentModelFactory.primary(properties, context);
        var summary =
                new ConfiguredCompactionModel(
                        AgentModelFactory.compaction(properties, context, primary),
                        context.getCompressionMaxOutputTokens(),
                        0,
                        context.getCompressionTimeout(),
                        1);
        var builder =
                HarnessAgent.builder()
                        .name("context-quality-acceptance")
                        .model(primary)
                        .stateStore(store)
                        .workspace(workspace)
                        .maxIters(2)
                        .sysPrompt("遵守会话中已确认的约束。回答只输出合法JSON，不要调用工具。")
                        .middleware(ContextCompactionTelemetry.beforeCompaction())
                        .middleware(ContextCompactionTelemetry.afterCompaction(true))
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableToolsConfig();
        new ContextRuntimeConfigurer(context).apply(builder, primary, summary);
        return new HarnessAgentRuntime(builder.build());
    }

    private static List<AgentRuntimeEvent> call(HarnessAgentRuntime runtime, String owner) {
        return runtime.stream(
                        AgentTurnRequest.builder()
                                .turnId(UUID.randomUUID().toString())
                                .ownerKey(owner)
                                .sessionId("session")
                                .message(
                                        "根据已确认的最新信息，只输出JSON："
                                                + "deliveryDate（交付日期）、reportFile（报告路径）、writeState（写调用状态）、"
                                                + "maySubmit（是否允许提交，布尔值）、mayRetryWrite（是否允许重试写入，布尔值）。")
                                .build())
                .collectList()
                .block(Duration.ofSeconds(90));
    }

    private static void assertAnswer(List<AgentRuntimeEvent> events) throws Exception {
        assertEquals(
                1,
                events.stream()
                        .filter(e -> e.getType() == AgentRuntimeEvent.Type.CONTEXT_COMPACTED)
                        .count());
        assertFalse(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.getType()
                                                == AgentRuntimeEvent.Type
                                                .CONTEXT_COMPACTION_FAILED));
        String text =
                events.stream()
                        .filter(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED)
                        .findFirst()
                        .orElseThrow()
                        .getText();
        var answer =
                new ObjectMapper()
                        .readTree(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
        assertEquals("2026-10-20", answer.path("deliveryDate").asText());
        assertEquals("/workspace/acceptance/final.json", answer.path("reportFile").asText());
        assertEquals("UNKNOWN", answer.path("writeState").asText());
        assertTrue(answer.path("maySubmit").isBoolean());
        assertFalse(answer.path("maySubmit").asBoolean());
        assertTrue(answer.path("mayRetryWrite").isBoolean());
        assertFalse(answer.path("mayRetryWrite").asBoolean());
    }

    private static List<Msg> history() {
        List<Msg> messages =
                new ArrayList<>(
                        List.of(
                                new UserMessage("持续约束：只生成草稿，未经用户批准禁止提交；写调用结果未知时禁止重复写入。"),
                                new AssistantMessage("已理解上述约束。"),
                                new UserMessage("报告用于合成验收，不涉及真实业务。")));
        for (int i = 0; i < 20; i++) {
            messages.add(new UserMessage(("讨论合成报告第" + i + "项格式，保留事实，不需要采取业务动作。").repeat(20)));
            messages.add(new AssistantMessage(("记录第" + i + "项格式要求。").repeat(10)));
            if (i == 4) {
                messages.add(new UserMessage("原定交付日期为2026-10-18。"));
                messages.add(new AssistantMessage("已记录原定日期。"));
            }
            if (i == 9) {
                messages.add(
                        new UserMessage(
                                "最新确认：交付日期改为2026-10-20，报告路径固定为"
                                        + "/workspace/acceptance/final.json。之前写调用超时，writeState=UNKNOWN；"
                                        + "没有批准提交，不能把超时当成失败后重试。"));
                messages.add(new AssistantMessage("已记录最新日期、路径和未知写调用状态，保持草稿且不重试写入。"));
            }
        }
        return messages;
    }

    private static String value(Properties local, String key) {
        return LiveConfiguration.value(local, key);
    }
}
