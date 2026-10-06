package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.bootstrap.WorkspaceTestConfiguration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 验证 Redis 状态丢失后，可从 JDBC 对话记录恢复已完成的对话。
 */
@EnabledIfSystemProperty(named = "horizen.redis.live", matches = "true")
@Import(WorkspaceTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "horizen.agent.model-mode=SCRIPTED",
                "horizen.agent.storage.mode=DISTRIBUTED",
                "horizen.agent.storage.jdbc-username=sa",
                "horizen.agent.storage.jdbc-password=",
                "horizen.agent.storage.redis-url=redis://127.0.0.1:6379",
                "horizen.agent.storage.redis-key-prefix=horizen-history-recovery-test:",
                "horizen.agent.storage.instance-id=history-recovery-instance",
                "horizen.agent.gateway.mode=remote",
                "horizen.agent.gateway.url=",
                "horizen.agent.gateway.token=",
                "horizen.trace.enabled=false",
                "horizen.agent.skill-release.enabled=false",
                "horizen.agent.workspace-release.enabled=false",
                "horizen.agent.sandbox.e2b.enabled=false",
                "horizen.agent.sandbox.snapshot.bos.enabled=false",
                "horizen.agent.artifact.bos.enabled=false"
        })
class HistoryRecoveryDistributedFlowLiveTest {
    private static final String DATABASE =
            "history_recovery_" + UUID.randomUUID().toString().replace("-", "");
    private static final String JDBC_URL =
            "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String SESSION_ID = "history-recovery-session-" + UUID.randomUUID();
    private static final ExecutionIdentity IDENTITY =
            new ExecutionIdentity("history-owner", "history-actor");
    private static final String REDIS_ROOT =
            "horizen-history-recovery-test:" + UUID.randomUUID() + ":";
    private static final String REDIS_PREFIX = REDIS_ROOT + "session:";

    @Autowired
    private AgentService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("horizen.agent.storage.jdbc-url", () -> JDBC_URL);
        registry.add("horizen.agent.storage.redis-key-prefix", () -> REDIS_ROOT);
    }

    @BeforeAll
    static void schema() {
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(JDBC_URL, "sa", ""));
    }

    @Test
    void recoversTranscriptAfterRedisAgentStateIsDeleted() {
        service.streamChat(
                        IDENTITY,
                        new ChatApi.ChatRequest(
                                SESSION_ID,
                                "history-first-message",
                                "history-first-request",
                                List.of()))
                .collectList()
                .block(Duration.ofSeconds(5));
        try (JedisPooled redis = new JedisPooled(URI.create("redis://127.0.0.1:6379"))) {
            String stateKey =
                    REDIS_PREFIX + IDENTITY.getOwnerKey() + "/" + SESSION_ID + ":agent_state";
            redis.del(stateKey, stateKey + ":ver");
        }

        service.streamChat(
                        IDENTITY,
                        new ChatApi.ChatRequest(
                                SESSION_ID,
                                "history-second-message",
                                "history-second-request",
                                List.of()))
                .collectList()
                .block(Duration.ofSeconds(5));

        SessionApi.SessionExecutionResponse execution =
                service.sessionExecution(IDENTITY, SESSION_ID);
        List<ChatApi.ChatStreamEvent> terminal =
                service.subscribeSession(
                                IDENTITY,
                                new SessionApi.SessionSubscribeRequest(
                                        SESSION_ID, execution.getCurrentTurnId()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertEquals(1, terminal.size());
        assertEquals("done", terminal.get(0).getType());

        try (JedisPooled redis = new JedisPooled(URI.create("redis://127.0.0.1:6379"))) {
            String stateKey =
                    REDIS_PREFIX + IDENTITY.getOwnerKey() + "/" + SESSION_ID + ":agent_state";
            String saved = redis.get(stateKey);
            assertTrue(saved != null && saved.contains("history-first-message"), saved);
        }
    }
}
