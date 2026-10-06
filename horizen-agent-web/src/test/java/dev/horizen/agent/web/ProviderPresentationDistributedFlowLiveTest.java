package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 验证 Provider 结果 → 展示事件 → 按所有者隔离的持久化刷新恢复流程。
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
                "horizen.agent.storage.redis-key-prefix=horizen-provider-presentation-test:",
                "horizen.agent.storage.instance-id=provider-presentation-instance",
                "horizen.agent.gateway.mode=mock",
                "horizen.trace.enabled=false",
                "horizen.agent.skill-release.enabled=false",
                "horizen.agent.workspace-release.enabled=false",
                "horizen.agent.sandbox.e2b.enabled=false",
                "horizen.agent.sandbox.snapshot.bos.enabled=false",
                "horizen.agent.artifact.bos.enabled=false"
        })
class ProviderPresentationDistributedFlowLiveTest {
    private static final String DATABASE =
            "provider_presentation_" + UUID.randomUUID().toString().replace("-", "");
    private static final String JDBC_URL =
            "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String SESSION = "provider-session-" + UUID.randomUUID();
    private static final ExecutionIdentity OWNER = new ExecutionIdentity("provider-owner", "actor");
    private static final String REDIS_PREFIX =
            "horizen-provider-presentation-test:" + UUID.randomUUID() + ":";
    private static final Path FIXTURE = fixture();

    @Autowired
    private AgentService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("horizen.agent.storage.jdbc-url", () -> JDBC_URL);
        registry.add("horizen.agent.storage.redis-key-prefix", () -> REDIS_PREFIX);
        registry.add("horizen.agent.gateway.fixture-file", () -> FIXTURE.toString());
    }

    @BeforeAll
    static void schema() {
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(JDBC_URL, "sa", ""));
    }

    @Test
    void persistsProviderPresentationAndRestoresItWithoutOwnerLeakage() throws Exception {
        List<ChatApi.ChatStreamEvent> events =
                service.streamChat(
                                OWNER,
                                new ChatApi.ChatRequest(
                                        SESSION,
                                        "provider presentation",
                                        "provider-request",
                                        List.of()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertTrue(
                events.stream().anyMatch(event -> "approval_required".equals(event.getType())),
                events.toString());
        SessionApi.SessionExecutionResponse waiting = service.sessionExecution(OWNER, SESSION);
        InteractionApi.ApprovalsResponse pending =
                service.pendingApprovals(
                        OWNER,
                        new InteractionApi.ApprovalQueryRequest(
                                SESSION, waiting.getCurrentTurnId()));
        service.decideApproval(
                OWNER,
                new InteractionApi.ApprovalDecisionRequest(
                        SESSION,
                        waiting.getCurrentTurnId(),
                        List.of(
                                new InteractionApi.ApprovalChoice(
                                        pending.getApprovals().get(0).getApprovalId(), true))));
        events =
                service.subscribeSession(
                                OWNER,
                                new SessionApi.SessionSubscribeRequest(
                                        SESSION, waiting.getCurrentTurnId()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertTrue(
                events.stream().anyMatch(event -> "presentation_created".equals(event.getType())),
                events.toString());
        assertTrue(events.stream().anyMatch(event -> "done".equals(event.getType())));

        SessionApi.SessionMessagesResponse restored = service.sessionMessages(OWNER, SESSION);
        assertEquals(1, restored.getPresentations().size());
        SessionApi.PresentationResponse card = restored.getPresentations().get(0);
        assertEquals("conclusion", card.getType());
        assertEquals("Provider 验收通过", card.getData().get("title"));
        assertTrue(
                restored.getTimelineEvents().stream()
                        .anyMatch(item -> "tool_start".equals(item.getEvent().getType())));
        assertTrue(
                restored.getTimelineEvents().stream()
                        .anyMatch(
                                item -> "presentation_created".equals(item.getEvent().getType())));
        assertTrue(
                restored.getTimelineEvents().stream()
                        .anyMatch(item -> "done".equals(item.getEvent().getType())));
        assertTrue(
                restored.getTimelineEvents().stream()
                        .noneMatch(
                                item ->
                                        "reasoning_delta".equals(item.getEvent().getType())
                                                || "thinking_delta"
                                                .equals(item.getEvent().getType())));

        var completedTool =
                restored.getTimelineEvents().stream()
                        .filter(item -> "tool_end".equals(item.getEvent().getType()))
                        .findFirst()
                        .orElseThrow()
                        .getEvent();
        var details = new ObjectMapper().readTree(completedTool.getDetails());
        assertEquals(1, details.path("toolCallSnapshot").path("version").asInt());
        assertTrue(details.path("toolCallSnapshot").path("output").asText().contains("ok"));

        SessionApi.SessionMessagesResponse isolated =
                service.sessionMessages(
                        new ExecutionIdentity("other-owner", "other-actor"), SESSION);
        assertTrue(isolated.getPresentations().isEmpty());
        assertTrue(isolated.getTimelineEvents().isEmpty());
    }

    private static Path fixture() {
        try {
            Path file = Files.createTempFile("horizen-provider-acceptance-", ".json");
            Files.writeString(
                    file,
                    """
                            {"tools":[{"name":"acceptance_provider_tool",
                              "description":"Deterministic Provider acceptance tool",
                              "inputSchema":{"type":"object","properties":{"query":{"type":"string"}},
                                "required":["query"],"additionalProperties":false},
                              "readOnly":false,"riskLevel":"high","approvalPolicy":"required",
                              "response":{"summary":"ok","presentation":{"schemaVersion":1,
                                "blocks":[{"type":"conclusion","severity":"good",
                                  "title":"Provider 验收通过","summary":"结构化结果已持久化"}]}}}]}
                            """);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
