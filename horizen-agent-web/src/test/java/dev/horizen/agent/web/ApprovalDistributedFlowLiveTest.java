package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.web.api.AgentApiMapper;
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

/** 需要本地 Redis，验证宿主级原生工具审批流程。 */
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
            "horizen.agent.storage.redis-key-prefix=horizen-approval-flow-test:",
            "horizen.agent.storage.instance-id=approval-flow-instance",
            "horizen.agent.gateway.mode=remote",
            "horizen.agent.gateway.url=",
            "horizen.agent.gateway.token=",
            "horizen.trace.enabled=false",
            "horizen.agent.workspace-release.enabled=false",
            "horizen.agent.skill-release.enabled=false",
            "horizen.agent.sandbox.e2b.enabled=false",
            "horizen.agent.sandbox.snapshot.bos.enabled=false",
            "horizen.agent.artifact.bos.enabled=false"
        })
class ApprovalDistributedFlowLiveTest {
    private static final String DATABASE =
            "approval_flow_" + UUID.randomUUID().toString().replace("-", "");
    private static final String JDBC_URL =
            "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String SESSION_ID =
            "approval-session-" + UUID.randomUUID().toString().replace("-", "");
    private static final String REDIS_PREFIX =
            "horizen-approval-flow-test:" + UUID.randomUUID() + ":";
    private static final ExecutionIdentity IDENTITY =
            new ExecutionIdentity("approval-owner", "approval-actor");

    @Autowired private AgentService service;

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
    void persistsApprovalThenContinuesTheSameTurn() {
        List<ChatApi.ChatStreamEvent> paused =
                service.streamChat(
                                IDENTITY,
                                new ChatApi.ChatRequest(
                                        SESSION_ID,
                                        "approval publish report",
                                        "approval-request",
                                        List.of()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertTrue(paused.stream().anyMatch(event -> "approval_required".equals(event.getType())));
        SessionApi.SessionExecutionResponse waiting =
                service.sessionExecution(IDENTITY, SESSION_ID);
        assertEquals("waiting_approval", waiting.getStatus());

        InteractionApi.ApprovalsResponse pending =
                service.pendingApprovals(
                        IDENTITY,
                        new InteractionApi.ApprovalQueryRequest(
                                SESSION_ID, waiting.getCurrentTurnId()));
        assertEquals(1, pending.getApprovals().size());
        assertEquals("确认执行演示操作", pending.getApprovals().get(0).getPresentation().getTitle());
        assertEquals(
                "approval publish report",
                pending.getApprovals().get(0).getPresentation().getFields().get("操作内容"));
        // 重新查询持久化快照，不从模型输出中重建结果。
        assertEquals(
                pending.getApprovals().get(0).getPresentation(),
                service.pendingApprovals(
                                IDENTITY,
                                new InteractionApi.ApprovalQueryRequest(
                                        SESSION_ID, waiting.getCurrentTurnId()))
                        .getApprovals()
                        .get(0)
                        .getPresentation());
        assertTrue(
                service.pendingApprovals(
                                new ExecutionIdentity("other-owner", "other-actor"),
                                new InteractionApi.ApprovalQueryRequest(
                                        SESSION_ID, waiting.getCurrentTurnId()))
                        .getApprovals()
                        .isEmpty());
        SessionApi.SessionExecutionResponse resumed =
                service.decideApproval(
                        IDENTITY,
                        new InteractionApi.ApprovalDecisionRequest(
                                SESSION_ID,
                                waiting.getCurrentTurnId(),
                                List.of(
                                        new InteractionApi.ApprovalChoice(
                                                pending.getApprovals().get(0).getApprovalId(),
                                                true))));
        assertEquals("running", resumed.getStatus());

        List<ChatApi.ChatStreamEvent> completed =
                service.subscribeSession(
                                IDENTITY,
                                new SessionApi.SessionSubscribeRequest(
                                        SESSION_ID, resumed.getCurrentTurnId()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertTrue(
                completed.stream().anyMatch(event -> "approval_resolved".equals(event.getType())));
        assertTrue(
                completed.stream()
                        .anyMatch(
                                event ->
                                        "done".equals(event.getType())
                                                && event.getText()
                                                        .contains(
                                                                "scripted-tool:approved:approval publish report")));
        assertEquals(
                TurnStatus.COMPLETED.name().toLowerCase(),
                service.sessionExecution(IDENTITY, SESSION_ID).getStatus());
        var history = service.sessionMessages(IDENTITY, SESSION_ID);
        assertTrue(
                history.getTimelineEvents().stream()
                        .noneMatch(
                                item ->
                                        "tool_input_delta".equals(item.getEvent().getType())
                                                || "tool_output_delta"
                                                        .equals(item.getEvent().getType())));
        var tool =
                history.getTimelineEvents().stream()
                        .map(SessionApi.TimelineEventResponse::getEvent)
                        .filter(event -> "tool_end".equals(event.getType()))
                        .findFirst()
                        .orElseThrow();
        var snapshot =
                (Map<?, ?>)
                        new AgentApiMapper(false)
                                .jsonMap(tool.getDetails())
                                .get("toolCallSnapshot");
        assertEquals("{\"value\":\"approval publish report\"}", snapshot.get("input"));
        assertTrue(snapshot.get("output").toString().contains("approved:approval publish report"));
    }

    @Test
    void rejectionResumesWithoutExecutingThePendingOperation() {
        String session = "reject-" + UUID.randomUUID();
        service.streamChat(
                        IDENTITY,
                        new ChatApi.ChatRequest(
                                session, "approval reject demo", "reject-request", List.of()))
                .collectList()
                .block(Duration.ofSeconds(5));
        var waiting = service.sessionExecution(IDENTITY, session);
        var pending =
                service.pendingApprovals(
                        IDENTITY,
                        new InteractionApi.ApprovalQueryRequest(
                                session, waiting.getCurrentTurnId()));
        service.decideApproval(
                IDENTITY,
                new InteractionApi.ApprovalDecisionRequest(
                        session,
                        waiting.getCurrentTurnId(),
                        List.of(
                                new InteractionApi.ApprovalChoice(
                                        pending.getApprovals().get(0).getApprovalId(), false))));
        var events =
                service.subscribeSession(
                                IDENTITY,
                                new SessionApi.SessionSubscribeRequest(
                                        session, waiting.getCurrentTurnId()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertTrue(events.stream().anyMatch(event -> "done".equals(event.getType())));
        assertTrue(
                events.stream()
                        .noneMatch(
                                event ->
                                        event.getText() != null
                                                && event.getText()
                                                        .contains(
                                                                "approved:approval reject demo")));
    }
}
