package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.web.AgentWebApplication;
import dev.horizen.agent.web.api.AgentApiMapper;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.interaction.InteractionApi;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.RuntimeStorageProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 两个独立应用宿主连接同一 H2 数据库和本地 Redis，验证接收交互回复的宿主无需持有暂停中的 Turn。
 */
@EnabledIfSystemProperty(named = "horizen.redis.live", matches = "true")
class CrossInstanceInteractionRecoveryLiveTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutionIdentity IDENTITY =
            new ExecutionIdentity("cross-owner", "cross-actor");

    @Test
    void instanceBResumesApprovalCreatedByInstanceA() {
        runAcrossInstances(
                (first, second, sessionId) -> {
                    List<ChatApi.ChatStreamEvent> paused =
                            first.streamChat(
                                            IDENTITY,
                                            new ChatApi.ChatRequest(
                                                    sessionId,
                                                    "approval cross instance",
                                                    "request-approval",
                                                    List.of()))
                                    .collectList()
                                    .block(WAIT);
                    assertTrue(
                            paused.stream()
                                    .anyMatch(
                                            event -> "approval_required".equals(event.getType())));

                    SessionApi.SessionExecutionResponse waiting =
                            second.sessionExecution(IDENTITY, sessionId);
                    InteractionApi.ApprovalsResponse pending =
                            second.pendingApprovals(
                                    IDENTITY,
                                    new InteractionApi.ApprovalQueryRequest(
                                            sessionId, waiting.getCurrentTurnId()));
                    assertEquals(1, pending.getApprovals().size());

                    second.decideApproval(
                            IDENTITY,
                            new InteractionApi.ApprovalDecisionRequest(
                                    sessionId,
                                    waiting.getCurrentTurnId(),
                                    List.of(
                                            new InteractionApi.ApprovalChoice(
                                                    pending.getApprovals().get(0).getApprovalId(),
                                                    true))));

                    List<ChatApi.ChatStreamEvent> recovered =
                            second.subscribeSession(
                                            IDENTITY,
                                            new SessionApi.SessionSubscribeRequest(
                                                    sessionId, waiting.getCurrentTurnId()))
                                    .collectList()
                                    .block(WAIT);
                    assertTrue(
                            recovered.stream()
                                    .anyMatch(
                                            event ->
                                                    "done".equals(event.getType())
                                                            && event.getText()
                                                                    .contains(
                                                                            "scripted-tool:approved:approval cross instance")));
                    assertEquals(
                            "completed", second.sessionExecution(IDENTITY, sessionId).getStatus());
                    var history = first.sessionMessages(IDENTITY, sessionId);
                    assertTrue(
                            history.getTimelineEvents().stream()
                                    .noneMatch(
                                            item ->
                                                    "tool_input_delta"
                                                                    .equals(
                                                                            item.getEvent()
                                                                                    .getType())
                                                            || "tool_output_delta"
                                                                    .equals(
                                                                            item.getEvent()
                                                                                    .getType())));
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
                    assertEquals("{\"value\":\"approval cross instance\"}", snapshot.get("input"));
                    assertTrue(
                            snapshot.get("output")
                                    .toString()
                                    .contains("approved:approval cross instance"));
                });
    }

    @Test
    void completedSubagentTreeCanBeRestoredByTheOtherInstance() {
        runAcrossInstances(
                (first, second, sessionId) -> {
                    var events =
                            first.streamChat(
                                            IDENTITY,
                                            new ChatApi.ChatRequest(
                                                    sessionId,
                                                    "delegate recovery",
                                                    "request-delegate",
                                                    List.of()))
                                    .collectList()
                                    .block(WAIT);
                    if (events.stream()
                            .anyMatch(event -> "approval_required".equals(event.getType()))) {
                        var waiting = second.sessionExecution(IDENTITY, sessionId);
                        var pending =
                                second.pendingApprovals(
                                        IDENTITY,
                                        new InteractionApi.ApprovalQueryRequest(
                                                sessionId, waiting.getCurrentTurnId()));
                        assertEquals("agent_spawn", pending.getApprovals().get(0).getToolName());
                        second.decideApproval(
                                IDENTITY,
                                new InteractionApi.ApprovalDecisionRequest(
                                        sessionId,
                                        waiting.getCurrentTurnId(),
                                        List.of(
                                                new InteractionApi.ApprovalChoice(
                                                        pending.getApprovals()
                                                                .get(0)
                                                                .getApprovalId(),
                                                        true))));
                        events =
                                second.subscribeSession(
                                                IDENTITY,
                                                new SessionApi.SessionSubscribeRequest(
                                                        sessionId, waiting.getCurrentTurnId()))
                                        .collectList()
                                        .block(WAIT);
                    }
                    assertEquals(
                            "completed", second.sessionExecution(IDENTITY, sessionId).getStatus());
                    var history = second.sessionMessages(IDENTITY, sessionId);
                    var tree =
                            history.getTimelineEvents().stream()
                                    .map(SessionApi.TimelineEventResponse::getEvent)
                                    .filter(event -> event.getSource() != null)
                                    .toList();
                    assertTrue(
                            tree.stream()
                                    .anyMatch(event -> "subagent_start".equals(event.getType())));
                    assertTrue(
                            tree.stream()
                                    .anyMatch(
                                            event ->
                                                    "subagent_result".equals(event.getType())
                                                            && event.getText()
                                                                    .contains(
                                                                            "child delegated task")));
                    assertTrue(
                            tree.stream()
                                    .anyMatch(event -> "subagent_end".equals(event.getType())));
                    assertTrue(
                            tree.stream()
                                    .allMatch(
                                            event ->
                                                    sessionId.equals(event.getParentSessionId())
                                                            && "general_worker"
                                                                    .equals(event.getAgentId())));
                    assertEquals(
                            1,
                            history.getTimelineEvents().stream()
                                    .filter(item -> "done".equals(item.getEvent().getType()))
                                    .count());
                    var other =
                            second.sessionMessages(
                                    new ExecutionIdentity("other-owner", "other-actor"), sessionId);
                    assertTrue(other.getTimelineEvents().isEmpty());
                });
    }

    @Test
    void childApprovalIsPresentedByTheParentAndResumedOnInstanceB() {
        runAcrossInstances(
                (first, second, sessionId) -> {
                    var events =
                            first.streamChat(
                                            IDENTITY,
                                            new ChatApi.ChatRequest(
                                                    sessionId,
                                                    "delegate approval",
                                                    "request-child-approval",
                                                    List.of()))
                                    .collectList()
                                    .block(WAIT);
                    var waiting = second.sessionExecution(IDENTITY, sessionId);
                    var pending =
                            second.pendingApprovals(
                                    IDENTITY,
                                    new InteractionApi.ApprovalQueryRequest(
                                            sessionId, waiting.getCurrentTurnId()));
                    // 此脚本宿主配置了显式策略，委派操作本身也可能需要授权。
                    if ("agent_spawn".equals(pending.getApprovals().get(0).getToolName())) {
                        second.decideApproval(
                                IDENTITY,
                                new InteractionApi.ApprovalDecisionRequest(
                                        sessionId,
                                        waiting.getCurrentTurnId(),
                                        List.of(
                                                new InteractionApi.ApprovalChoice(
                                                        pending.getApprovals()
                                                                .get(0)
                                                                .getApprovalId(),
                                                        true))));
                        events =
                                second.subscribeSession(
                                                IDENTITY,
                                                new SessionApi.SessionSubscribeRequest(
                                                        sessionId, waiting.getCurrentTurnId()))
                                        .collectList()
                                        .block(WAIT);
                        pending =
                                second.pendingApprovals(
                                        IDENTITY,
                                        new InteractionApi.ApprovalQueryRequest(
                                                sessionId, waiting.getCurrentTurnId()));
                    }
                    assertEquals(1, pending.getApprovals().size());
                    assertEquals(
                            "scripted_dangerous_action",
                            pending.getApprovals().get(0).getToolName());
                    assertEquals(
                            "scripted-parent-action-call",
                            pending.getApprovals().get(0).getToolCallId());
                    assertTrue(events.stream().noneMatch(event -> "done".equals(event.getType())));
                    assertEquals(
                            "waiting_approval",
                            second.sessionExecution(IDENTITY, sessionId).getStatus());
                    second.decideApproval(
                            IDENTITY,
                            new InteractionApi.ApprovalDecisionRequest(
                                    sessionId,
                                    waiting.getCurrentTurnId(),
                                    List.of(
                                            new InteractionApi.ApprovalChoice(
                                                    pending.getApprovals().get(0).getApprovalId(),
                                                    true))));
                    var done =
                            second.subscribeSession(
                                            IDENTITY,
                                            new SessionApi.SessionSubscribeRequest(
                                                    sessionId, waiting.getCurrentTurnId()))
                                    .collectList()
                                    .block(WAIT);
                    assertTrue(
                            done.stream()
                                    .anyMatch(
                                            event ->
                                                    "done".equals(event.getType())
                                                            && event.getText()
                                                                    .contains(
                                                                            "approved:child approval delegated task")));
                    var history = first.sessionMessages(IDENTITY, sessionId);
                    assertEquals(
                            1,
                            history.getTimelineEvents().stream()
                                    .filter(
                                            item ->
                                                    "tool_end".equals(item.getEvent().getType())
                                                            && "scripted-parent-action-call"
                                                                    .equals(item.getEvent().getId())
                                                            && "success"
                                                                    .equals(
                                                                            item.getEvent()
                                                                                    .getStatus()))
                                    .count());
                });
    }

    @Test
    void childClarificationUsesTheParentSessionAndResumesOnInstanceB() {
        runAcrossInstances(
                (first, second, sessionId) -> {
                    var events =
                            first.streamChat(
                                            IDENTITY,
                                            new ChatApi.ChatRequest(
                                                    sessionId,
                                                    "delegate clarification",
                                                    "request-child-ask",
                                                    List.of()))
                                    .collectList()
                                    .block(WAIT);
                    var waiting = second.sessionExecution(IDENTITY, sessionId);
                    if ("waiting_approval".equals(waiting.getStatus())) {
                        var pending =
                                second.pendingApprovals(
                                        IDENTITY,
                                        new InteractionApi.ApprovalQueryRequest(
                                                sessionId, waiting.getCurrentTurnId()));
                        assertEquals("agent_spawn", pending.getApprovals().get(0).getToolName());
                        second.decideApproval(
                                IDENTITY,
                                new InteractionApi.ApprovalDecisionRequest(
                                        sessionId,
                                        waiting.getCurrentTurnId(),
                                        List.of(
                                                new InteractionApi.ApprovalChoice(
                                                        pending.getApprovals()
                                                                .get(0)
                                                                .getApprovalId(),
                                                        true))));
                        events =
                                second.subscribeSession(
                                                IDENTITY,
                                                new SessionApi.SessionSubscribeRequest(
                                                        sessionId, waiting.getCurrentTurnId()))
                                        .collectList()
                                        .block(WAIT);
                    }
                    var required =
                            events.stream()
                                    .filter(event -> "ask_user_required".equals(event.getType()))
                                    .findFirst()
                                    .orElseThrow();
                    var request = new AgentApiMapper(false).jsonMap(required.getDetails());
                    assertEquals(sessionId, request.get("sessionId"));
                    assertEquals("scripted-parent-action-call", request.get("toolCallId"));
                    assertTrue(events.stream().noneMatch(event -> "done".equals(event.getType())));
                    String id = request.get("askUserId").toString();
                    second.answerAskUser(
                            IDENTITY,
                            new InteractionApi.AskUserAnswerRequest(
                                    id,
                                    List.of(
                                            Map.of(
                                                    "questionId",
                                                    "scope",
                                                    "selectedOptionIds",
                                                    List.of("week"),
                                                    "customText",
                                                    "")),
                                    false));
                    var done =
                            second.subscribeSession(
                                            IDENTITY,
                                            new SessionApi.SessionSubscribeRequest(
                                                    sessionId, waiting.getCurrentTurnId()))
                                    .collectList()
                                    .block(WAIT);
                    assertTrue(
                            done.stream()
                                    .anyMatch(
                                            event ->
                                                    "done".equals(event.getType())
                                                            && event.getText().contains("week")));
                    assertEquals(
                            "completed", second.sessionExecution(IDENTITY, sessionId).getStatus());
                });
    }

    @Test
    void instanceBResumesAskUserCreatedByInstanceA() {
        runAcrossInstances(
                (first, second, sessionId) -> {
                    List<ChatApi.ChatStreamEvent> paused =
                            first.streamChat(
                                            IDENTITY,
                                            new ChatApi.ChatRequest(
                                                    sessionId,
                                                    "ask cross instance",
                                                    "request-ask",
                                                    List.of()))
                                    .collectList()
                                    .block(WAIT);
                    String askUserId =
                            paused.stream()
                                    .filter(event -> "ask_user_required".equals(event.getType()))
                                    .map(CrossInstanceInteractionRecoveryLiveTest::askUserId)
                                    .findFirst()
                                    .orElseThrow();

                    SessionApi.SessionExecutionResponse waiting =
                            second.sessionExecution(IDENTITY, sessionId);
                    assertEquals("waiting_ask_user", waiting.getStatus());
                    second.answerAskUser(
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

                    List<ChatApi.ChatStreamEvent> recovered =
                            second.subscribeSession(
                                            IDENTITY,
                                            new SessionApi.SessionSubscribeRequest(
                                                    sessionId, waiting.getCurrentTurnId()))
                                    .collectList()
                                    .block(WAIT);
                    assertTrue(
                            recovered.stream()
                                    .anyMatch(
                                            event ->
                                                    "done".equals(event.getType())
                                                            && event.getText()
                                                                    .contains("scripted-tool")));
                    assertEquals(
                            "completed", second.sessionExecution(IDENTITY, sessionId).getStatus());
                });
    }

    @Test
    void instanceBCancelsActiveTurnOwnedByInstanceA() {
        runAcrossInstances(
                (first, second, sessionId) -> {
                    Disposable running =
                            first.streamChat(
                                            IDENTITY,
                                            new ChatApi.ChatRequest(
                                                    sessionId,
                                                    "wait cross instance",
                                                    "request-cancel",
                                                    List.of()))
                                    .subscribe();
                    try {
                        waitUntil(
                                () ->
                                        "running"
                                                .equals(
                                                        second.sessionExecution(IDENTITY, sessionId)
                                                                .getStatus()));
                        SessionApi.SessionExecutionResponse active =
                                second.sessionExecution(IDENTITY, sessionId);

                        second.cancelSession(
                                IDENTITY,
                                new SessionApi.SessionCancelRequest(
                                        sessionId, active.getCurrentTurnId()));

                        List<ChatApi.ChatStreamEvent> recovered =
                                second.subscribeSession(
                                                IDENTITY,
                                                new SessionApi.SessionSubscribeRequest(
                                                        sessionId, active.getCurrentTurnId()))
                                        .collectList()
                                        .block(WAIT);
                        assertTrue(
                                recovered.stream()
                                        .anyMatch(event -> "cancelled".equals(event.getType())));
                        waitUntil(
                                () ->
                                        "cancelled"
                                                .equals(
                                                        second.sessionExecution(IDENTITY, sessionId)
                                                                .getStatus()));
                    } finally {
                        running.dispose();
                    }
                });
    }

    @Test
    void instanceBMarksExpiredLeaseAsLostExecutor() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl =
                "jdbc:h2:mem:lost_executor_"
                        + suffix
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String redisPrefix = "horizen-lost-executor-test:" + suffix + ":";
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        String sessionId = "lost-session-" + suffix;
        String turnId = "lost-turn-" + suffix;
        Instant now = Instant.now();
        try (RuntimeStorage lost =
                RuntimeStorage.open(
                        storageProperties(
                                jdbcUrl,
                                redisPrefix,
                                "lost-instance",
                                Duration.ofSeconds(30),
                                Duration.ofMillis(50)),
                        InMemoryWorkspaceContentRepository.shared("runtime-storage-tests"))) {
            lost.getSessionTurns()
                    .startTurn(
                            new StartTurnCommand(
                                    IDENTITY,
                                    sessionId,
                                    turnId,
                                    "lost-request",
                                    "lost-instance",
                                    "lost-message",
                                    "wait lost executor",
                                    now.minusSeconds(2),
                                    now.plusSeconds(60),
                                    now.minusSeconds(1)));
        }
        try (ConfigurableApplicationContext second =
                host(
                        jdbcUrl,
                        redisPrefix,
                        "instance-b",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(50))) {
            AgentService service = second.getBean(AgentService.class);
            waitUntil(
                    () ->
                            "failed"
                                    .equals(
                                            service.sessionExecution(IDENTITY, sessionId)
                                                    .getStatus()));
            List<ChatApi.ChatStreamEvent> recovered =
                    service.subscribeSession(
                                    IDENTITY,
                                    new SessionApi.SessionSubscribeRequest(sessionId, turnId))
                            .collectList()
                            .block(WAIT);
            assertTrue(
                    recovered.stream()
                            .anyMatch(
                                    event ->
                                            "error".equals(event.getType())
                                                    && event.getDetails() != null
                                                    && event.getDetails()
                                                            .contains("EXECUTOR_LOST")));
        }
    }

    private static void runAcrossInstances(InstanceScenario scenario) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl =
                "jdbc:h2:mem:cross_instance_"
                        + suffix
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String redisPrefix = "horizen-cross-instance-test:" + suffix + ":";
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        try (ConfigurableApplicationContext first =
                        host(
                                jdbcUrl,
                                redisPrefix,
                                "instance-a",
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(10));
                ConfigurableApplicationContext second =
                        host(
                                jdbcUrl,
                                redisPrefix,
                                "instance-b",
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(10))) {
            scenario.run(
                    first.getBean(AgentService.class),
                    second.getBean(AgentService.class),
                    "cross-session-" + suffix);
        }
    }

    private static ConfigurableApplicationContext host(
            String jdbcUrl,
            String redisPrefix,
            String instanceId,
            Duration leaseTtl,
            Duration leaseHeartbeat) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put("horizen.agent.model-mode", "SCRIPTED");
        properties.put("horizen.agent.storage.mode", "DISTRIBUTED");
        properties.put("horizen.agent.storage.jdbc-url", jdbcUrl);
        properties.put("horizen.agent.storage.jdbc-username", "sa");
        properties.put("horizen.agent.storage.jdbc-password", "");
        properties.put(
                "horizen.agent.storage.redis-url",
                System.getProperty("horizen.redis.url", "redis://127.0.0.1:6379"));
        properties.put("horizen.agent.storage.redis-key-prefix", redisPrefix);
        properties.put("horizen.agent.storage.instance-id", instanceId);
        properties.put("horizen.agent.storage.lease-ttl", leaseTtl.toString());
        properties.put("horizen.agent.storage.lease-heartbeat", leaseHeartbeat.toString());
        properties.put("horizen.agent.gateway.mode", "remote");
        properties.put("horizen.agent.gateway.url", "");
        properties.put("horizen.agent.gateway.token", "");
        properties.put("horizen.trace.enabled", "false");
        properties.put("horizen.agent.skill-release.enabled", "false");
        properties.put("horizen.agent.workspace-release.enabled", "false");
        properties.put("horizen.agent.sandbox.e2b.enabled", "false");
        properties.put("horizen.agent.sandbox.snapshot.bos.enabled", "false");
        properties.put("horizen.agent.artifact.bos.enabled", "false");
        return new SpringApplicationBuilder(
                        AgentWebApplication.class, WorkspaceTestConfiguration.class)
                .web(WebApplicationType.NONE)
                .initializers(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "cross-instance-test", properties)))
                .run();
    }

    private static RuntimeStorageProperties storageProperties(
            String jdbcUrl,
            String redisPrefix,
            String instanceId,
            Duration leaseTtl,
            Duration leaseHeartbeat) {
        return new RuntimeStorageProperties(
                RuntimeStorageProperties.Mode.DISTRIBUTED,
                jdbcUrl,
                "sa",
                "",
                4,
                4,
                System.getProperty("horizen.redis.url", "redis://127.0.0.1:6379"),
                redisPrefix,
                instanceId,
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                leaseTtl,
                leaseHeartbeat,
                Duration.ofMillis(50));
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting", interrupted);
            }
        }
        throw new AssertionError("condition did not become true within " + WAIT);
    }

    private static String askUserId(ChatApi.ChatStreamEvent event) {
        try {
            Map<String, Object> details =
                    JSON.readValue(event.getDetails(), new TypeReference<Map<String, Object>>() {});
            Object askUserId = details.get("askUserId");
            if (askUserId == null || askUserId.toString().isBlank()) {
                throw new IllegalStateException("ask_user event has no askUserId");
            }
            return askUserId.toString();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("ask_user event details are not valid JSON", error);
        }
    }

    @FunctionalInterface
    private interface InstanceScenario {
        void run(AgentService first, AgentService second, String sessionId);
    }
}
