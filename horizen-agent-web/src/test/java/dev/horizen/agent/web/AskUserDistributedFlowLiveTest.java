package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.interaction.InteractionApi;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 需要本地 Redis，验证宿主级 ask_user 暂停和恢复契约。
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
                "horizen.agent.storage.redis-key-prefix=horizen-ask-flow-test:",
                "horizen.agent.storage.instance-id=ask-flow-instance",
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
class AskUserDistributedFlowLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATABASE =
            "ask_flow_" + UUID.randomUUID().toString().replace("-", "");
    private static final String JDBC_URL =
            "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String SESSION_ID =
            "ask-session-" + UUID.randomUUID().toString().replace("-", "");
    private static final String REDIS_PREFIX = "horizen-ask-flow-test:" + UUID.randomUUID() + ":";
    private static final ExecutionIdentity IDENTITY =
            new ExecutionIdentity("ask-owner", "ask-actor");

    @Autowired
    private AgentService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("horizen.agent.storage.jdbc-url", () -> JDBC_URL);
        registry.add("horizen.agent.storage.redis-key-prefix", () -> REDIS_PREFIX);
    }

    @BeforeAll
    static void schema() {
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(JDBC_URL, "sa", ""));
    }

    @Test
    void persistsPauseThenResumesSameTurnAfterAnswer() throws Exception {
        List<ChatApi.ChatStreamEvent> paused =
                service.streamChat(
                                IDENTITY,
                                new ChatApi.ChatRequest(
                                        SESSION_ID, "ask for scope", "ask-request", List.of()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        ChatApi.ChatStreamEvent required =
                paused.stream()
                        .filter(event -> "ask_user_required".equals(event.getType()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(paused.toString()));
        assertEquals(
                "waiting_ask_user", service.sessionExecution(IDENTITY, SESSION_ID).getStatus());

        Map<String, Object> details =
                JSON.readValue(required.getDetails(), new TypeReference<>() {
                });
        String askUserId = String.valueOf(details.get("askUserId"));
        SessionApi.SessionExecutionResponse resumed =
                service.answerAskUser(
                        IDENTITY,
                        new InteractionApi.AskUserAnswerRequest(
                                askUserId,
                                List.of(
                                        Map.of(
                                                "questionId",
                                                "scope",
                                                "selectedOptionIds",
                                                List.of("week"),
                                                "customText",
                                                "")),
                                false));
        assertEquals("running", resumed.getStatus());

        List<ChatApi.ChatStreamEvent> completed =
                service.subscribeSession(
                                IDENTITY,
                                new SessionApi.SessionSubscribeRequest(
                                        SESSION_ID, resumed.getCurrentTurnId()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertTrue(
                completed.stream().anyMatch(event -> "ask_user_resolved".equals(event.getType())));
        assertTrue(
                completed.stream()
                        .anyMatch(
                                event ->
                                        "done".equals(event.getType())
                                                && event.getText().contains("scripted-tool")));
        assertEquals(
                TurnStatus.COMPLETED.name().toLowerCase(),
                service.sessionExecution(IDENTITY, SESSION_ID).getStatus());
    }

    @Test
    void emitsPersistentTodoProgressForTheSession() throws Exception {
        String sessionId = "todo-session-" + UUID.randomUUID().toString().replace("-", "");
        List<ChatApi.ChatStreamEvent> events =
                service.streamChat(
                                IDENTITY,
                                new ChatApi.ChatRequest(
                                        sessionId, "todo for diagnosis", "todo-request", List.of()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        ChatApi.ChatStreamEvent todo =
                events.stream()
                        .filter(event -> "todo_updated".equals(event.getType()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(events.toString()));
        List<Map<String, Object>> tasks =
                JSON.readValue(todo.getDetails(), new TypeReference<>() {
                });
        assertEquals(2, tasks.size());
        assertEquals("completed", tasks.get(0).get("status"));
        assertEquals("in_progress", tasks.get(1).get("status"));
    }
}
