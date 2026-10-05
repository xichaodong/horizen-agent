package dev.horizen.agent.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.web.api.artifact.ArtifactApi.ArtifactResponse;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.chat.ChatApi.ChatStreamEvent;
import dev.horizen.agent.web.api.interaction.InteractionApi.ApprovalResponse;
import dev.horizen.agent.web.api.interaction.InteractionApi.ApprovalsResponse;
import dev.horizen.agent.web.api.session.SessionApi.ConversationMessageResponse;
import dev.horizen.agent.web.api.session.SessionApi.SessionExecutionResponse;
import dev.horizen.agent.web.api.session.SessionApi.SessionListResponse;
import dev.horizen.agent.web.api.session.SessionApi.SessionMessagesResponse;
import dev.horizen.agent.web.api.session.SessionApi.SessionSummaryResponse;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.SseProperties;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class AgentControllerStreamTest {
    @Test
    void frameworkHttpFailureKeepsStatusAndReturnsVisibleJson() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.subscribeSession(any(), any()))
                .thenThrow(
                        new ResponseStatusException(
                                HttpStatus.UNAUTHORIZED, "synthetic private reason"));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(controller(service))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        mvc.perform(
                        post("/api/session/subscribe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content("{\"sessionId\":\"session\",\"expectedTurnId\":\"turn\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("身份验证失败，请重新登录后重试。"));
    }

    @Test
    void unexpectedApiFailureReturnsSafeJsonEvenForSseAcceptHeader() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.subscribeSession(any(), any()))
                .thenThrow(new IllegalStateException("synthetic private detail"));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(controller(service))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        mvc.perform(
                        post("/api/session/subscribe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content("{\"sessionId\":\"session\",\"expectedTurnId\":\"turn\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("请求处理出现异常，当前结果尚未确认。"));
    }

    private static final ExecutionIdentity IDENTITY =
            new ExecutionIdentity("test-owner", "test-actor");

    private static AgentController controller(AgentService service) {
        return new AgentController(service, request -> IDENTITY);
    }

    @Test
    void exposesExecutionEvidenceAndReplyTextButNotThinkingEvents() {
        assertFalse(
                AgentService.userVisibleEvent(runtimeEvent(AgentRuntimeEvent.Type.THINKING_DELTA)));
        assertTrue(AgentService.userVisibleEvent(runtimeEvent(AgentRuntimeEvent.Type.TEXT_DELTA)));
        assertFalse(
                AgentService.userVisibleEvent(runtimeEvent(AgentRuntimeEvent.Type.MODEL_STARTED)));
        assertTrue(
                AgentService.userVisibleEvent(runtimeEvent(AgentRuntimeEvent.Type.TOOL_STARTED)));
        assertTrue(
                AgentService.userVisibleEvent(runtimeEvent(AgentRuntimeEvent.Type.TODO_UPDATED)));
    }

    private static AgentRuntimeEvent runtimeEvent(AgentRuntimeEvent.Type type) {
        return new AgentRuntimeEvent(
                type, "turn", "session", "event", null, null, null, null, null, null, null);
    }

    @Test
    void invalidSseSubscriptionReturnsTheOriginalApiErrorAsJson() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.subscribeSession(any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "turnId 非法"));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(controller(service))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        mvc.perform(
                        post("/api/session/subscribe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content("{\"sessionId\":\"test-session\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("turnId 非法"));
    }

    @Test
    void queriesAndDecidesApprovalUsingPostBodies() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.pendingApprovals(any(), any()))
                .thenReturn(
                        new ApprovalsResponse(
                                "test-session",
                                "turn-1",
                                List.of(
                                        new ApprovalResponse(
                                                "approval-1",
                                                "turn-1",
                                                "tool-1",
                                                "dangerous",
                                                Map.of("value", "run"),
                                                "pending",
                                                Instant.parse("2026-09-24T00:10:00Z"),
                                                null))));
        when(service.decideApproval(any(), any()))
                .thenReturn(
                        new SessionExecutionResponse(
                                "test-session",
                                "turn-1",
                                "running",
                                Instant.parse("2026-09-24T00:00:00Z"),
                                null,
                                null));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mockMvc.perform(
                        post("/api/session/approvals/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"test-session\",\"turnId\":\"turn-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvals[0].toolName").value("dangerous"));
        mockMvc.perform(
                        post("/api/session/approval/decide")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"sessionId\":\"test-session\",\"turnId\":\"turn-1\","
                                                + "\"decisions\":[{\"approvalId\":\"approval-1\","
                                                + "\"approved\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void queriesDurableConversationMessagesWithPostBody() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.sessionMessages(any(), ArgumentMatchers.eq("test-session")))
                .thenReturn(
                        new SessionMessagesResponse(
                                "test-session",
                                List.of(
                                        new ConversationMessageResponse(
                                                "message-1",
                                                "turn-1",
                                                "user",
                                                "你好",
                                                1,
                                                Instant.parse("2026-09-24T00:00:00Z"),
                                                List.of(
                                                        new ArtifactResponse(
                                                                "artifact-1",
                                                                "FILE",
                                                                "READY",
                                                                "source.xlsx",
                                                                "application/vnd.openxmlformats-officedocument."
                                                                        + "spreadsheetml.sheet",
                                                                1024L,
                                                                null))))));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mockMvc.perform(
                        post("/api/session/messages/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"test-session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("你好"))
                .andExpect(jsonPath("$.messages[0].attachments[0].artifactId").value("artifact-1"));
    }

    @Test
    void managesServerBackedSessionCatalog() throws Exception {
        AgentService service = mock(AgentService.class);
        SessionSummaryResponse summary =
                new SessionSummaryResponse(
                        "test-session",
                        "第一轮问题",
                        true,
                        "completed",
                        "turn-1",
                        null,
                        Instant.parse("2026-09-24T00:01:00Z"),
                        Instant.parse("2026-09-24T00:00:00Z"),
                        Instant.parse("2026-09-24T00:02:00Z"));
        when(service.listSessions(any(), any()))
                .thenReturn(new SessionListResponse(List.of(summary), false, null));
        when(service.renameSession(any(), any())).thenReturn(summary);
        when(service.setSessionPinned(any(), any())).thenReturn(summary);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mockMvc.perform(
                        post("/api/sessions/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"limit\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].sessionId").value("test-session"))
                .andExpect(jsonPath("$.sessions[0].pinned").value(true));
        mockMvc.perform(
                        post("/api/session/rename")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"test-session\",\"title\":\"新名称\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/session/pin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"test-session\",\"pinned\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/session/delete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"test-session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(service).deleteSession(eq(IDENTITY), any());
    }

    @Test
    void exposesLatestTurnStateForTheSession() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.sessionExecution(any(), ArgumentMatchers.eq("test-session")))
                .thenReturn(
                        new SessionExecutionResponse(
                                "test-session",
                                "turn-7",
                                "running",
                                Instant.parse("2026-09-24T00:00:00Z"),
                                null,
                                null));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mockMvc.perform(
                        post("/api/session/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"test-session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTurnId").value("turn-7"))
                .andExpect(jsonPath("$.status").value("running"));
        verify(service).sessionExecution(eq(IDENTITY), eq("test-session"));
    }

    @Test
    void reconnectsAndCancelsUsingPostBodies() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.streamTimeout()).thenReturn(Duration.ofMinutes(10));
        when(service.subscribeSession(any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatStreamEvent(
                                        "turn_start",
                                        "turn-7",
                                        "开始处理",
                                        null,
                                        "running",
                                        null,
                                        null,
                                        null,
                                        null),
                                new ChatStreamEvent(
                                        "done", "turn-7", "最终回复", "完成", "success", null, null, 12L,
                                        12L)));
        when(service.cancelSession(any(), any()))
                .thenReturn(
                        new SessionExecutionResponse(
                                "test-session",
                                "turn-7",
                                "cancelled",
                                Instant.parse("2026-09-24T00:00:00Z"),
                                Instant.parse("2026-09-24T00:00:01Z"),
                                null));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        MvcResult pending =
                mockMvc.perform(
                                post("/api/session/subscribe")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.TEXT_EVENT_STREAM)
                                        .content(
                                                "{\"sessionId\":\"test-session\","
                                                        + "\"expectedTurnId\":\"turn-7\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/session/cancel")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"sessionId\":\"test-session\","
                                                + "\"expectedTurnId\":\"turn-7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void streamsDeltaAndDoneEventsAsSse() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.streamTimeout()).thenReturn(Duration.ofMinutes(10));
        when(service.streamChat(any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatStreamEvent(
                                        "turn_start",
                                        "turn",
                                        "开始处理",
                                        null,
                                        "running",
                                        null,
                                        null,
                                        null,
                                        null),
                                new ChatStreamEvent(
                                        "reasoning_delta",
                                        "reply-1",
                                        "思考过程",
                                        "分析问题",
                                        "running",
                                        null,
                                        null,
                                        null,
                                        null),
                                new ChatStreamEvent(
                                        "tool_start",
                                        "tool-1",
                                        "读取 Skill",
                                        null,
                                        "running",
                                        "load_skill_through_path",
                                        null,
                                        null,
                                        null),
                                new ChatStreamEvent(
                                        "tool_end",
                                        "tool-1",
                                        "读取 Skill",
                                        null,
                                        "success",
                                        "load_skill_through_path",
                                        null,
                                        18L,
                                        null),
                                new ChatStreamEvent(
                                        "subagent_start",
                                        "child-reply",
                                        "子 Agent 开始执行",
                                        null,
                                        "running",
                                        null,
                                        null,
                                        null,
                                        null,
                                        "test-session/general_worker",
                                        "task-child",
                                        "test-session",
                                        "general_worker",
                                        1),
                                new ChatStreamEvent(
                                        "done", "turn", "最终回复", "你好", "success", null, null, 42L,
                                        42L)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        MvcResult pending =
                mockMvc.perform(
                                post("/api/chat/stream")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.TEXT_EVENT_STREAM)
                                        .content(
                                                "{\"sessionId\":\"test-session\",\"message\":\"hello\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();

        MvcResult completed =
                mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(
                completed
                        .getResponse()
                        .getContentType()
                        .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
        assertTrue(body.contains("event:reasoning_delta"));
        assertTrue(body.contains("event:subagent_start"));
        assertTrue(body.contains("\"source\":\"test-session/general_worker\""));
        assertTrue(body.contains("\"taskId\":\"task-child\""));
        assertTrue(body.contains("\"agentId\":\"general_worker\""));
        assertTrue(body.contains("\"text\":\"分析问题\""));
        assertTrue(body.contains("event:tool_start"));
        assertTrue(body.contains("\"toolName\":\"load_skill_through_path\""));
        assertTrue(body.contains("\"durationMs\":18"));
        assertTrue(body.contains("event:done"));
        assertTrue(body.contains("\"text\":\"你好\""));
    }

    @Test
    void rejectsNewSseWhenInstanceConnectionLimitIsReached() {
        AgentService service = mock(AgentService.class);
        when(service.streamTimeout()).thenReturn(Duration.ofMinutes(10));
        when(service.streamChat(any(), any())).thenReturn(Flux.never());
        SseProperties properties = new SseProperties();
        properties.setMaxConnections(1);
        AgentController controller = new AgentController(service, request -> IDENTITY, properties);
        ChatApi.ChatRequest request =
                new ChatApi.ChatRequest("session", "message", "request", List.of());

        var first = controller.streamChat(request, null);
        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class, () -> controller.streamChat(request, null));
        assertEquals(429, error.getStatusCode().value());
        first.complete();
        controller.close();
    }

    @Test
    void closesSlowConnectionWhenItsOutboundQueueFillsAndReleasesInstanceCapacity()
            throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.streamTimeout()).thenReturn(Duration.ofMinutes(10));
        SseProperties properties = new SseProperties();
        properties.setMaxConnections(1);
        properties.setOutboundMaxEvents(1);
        properties.setOutboundMaxBytes(1024 * 1024);
        properties.setWriterCoreThreads(1);
        properties.setWriterMaxThreads(1);
        properties.setWriterQueueCapacity(1);
        AgentController controller = new AgentController(service, request -> IDENTITY, properties);
        BlockingEmitter slow = new BlockingEmitter();
        ChatStreamEvent first =
                new ChatStreamEvent(
                        "text_delta", "one", "", "one", "running", null, null, null, null);
        ChatStreamEvent second =
                new ChatStreamEvent(
                        "text_delta", "two", "", "two", "running", null, null, null, null);
        ChatStreamEvent third =
                new ChatStreamEvent(
                        "text_delta", "three", "", "three", "running", null, null, null, null);

        Sinks.Many<ChatStreamEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        controller.stream(source.asFlux(), slow);
        source.tryEmitNext(first);
        assertTrue(slow.sendStarted.await(1, TimeUnit.SECONDS));
        source.tryEmitNext(second);
        source.tryEmitNext(third);
        assertTrue(slow.failure.await(1, TimeUnit.SECONDS));
        assertTrue(slow.error.get() instanceof IllegalStateException);
        AgentController.StreamRuntimeStatus status = controller.streamStatus();
        assertEquals(0, status.getActiveConnections());
        assertEquals(0, status.getQueuedEvents());
        assertEquals(0L, status.getQueuedBytes());
        assertEquals(1L, status.getSlowClientCloses());
        assertTrue(status.getProcess().getHeapUsedBytes() > 0L);
        assertTrue(status.getProcess().getLiveThreads() > 0);

        slow.release.countDown();
        SseEmitter recovered = controller.stream(Flux.never(), new SseEmitter());
        recovered.complete();
        controller.close();
    }

    @Test
    void exposesProcessAndConnectionPoolCapacity() {
        AgentService service = mock(AgentService.class);
        RuntimeStorage.DatabasePoolStatus databasePool =
                new RuntimeStorage.DatabasePoolStatus(3, 4, 7, 1, 10);
        RuntimeStorage.RedisPoolsStatus redisPools =
                new RuntimeStorage.RedisPoolsStatus(
                        new RuntimeStorage.RedisPoolStatus(2, 3, 0, 8),
                        new RuntimeStorage.RedisPoolStatus(0, 0, 0, 8));
        when(service.databasePoolStatus()).thenReturn(databasePool);
        when(service.redisPoolsStatus()).thenReturn(redisPools);
        AgentController controller = controller(service);

        AgentController.StreamRuntimeStatus status = controller.streamStatus();

        assertEquals(databasePool, status.getDatabasePool());
        assertEquals(redisPools, status.getRedisPools());
        assertTrue(status.getProcess().getHeapUsedBytes() > 0L);
        assertTrue(status.getProcess().getLiveThreads() > 0);
        assertTrue(status.getProcess().getAvailableProcessors() > 0);
        controller.close();
    }

    @Test
    void closesSlowConnectionWhenQueuedEventWaitsTooLong() throws Exception {
        AgentService service = mock(AgentService.class);
        SseProperties properties = new SseProperties();
        properties.setMaxQueueWait(Duration.ofMillis(20));
        properties.setOutboundMaxEvents(2);
        properties.setWriterCoreThreads(1);
        properties.setWriterMaxThreads(1);
        AgentController controller = new AgentController(service, request -> IDENTITY, properties);
        BlockingEmitter slow = new BlockingEmitter();
        ChatStreamEvent first =
                new ChatStreamEvent(
                        "text_delta", "one", "", "one", "running", null, null, null, null);
        ChatStreamEvent second =
                new ChatStreamEvent(
                        "text_delta", "two", "", "two", "running", null, null, null, null);

        Sinks.Many<ChatStreamEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        controller.stream(source.asFlux(), slow);
        source.tryEmitNext(first);
        assertTrue(slow.sendStarted.await(1, TimeUnit.SECONDS));
        source.tryEmitNext(second);
        Thread.sleep(50L);
        slow.release.countDown();

        assertTrue(slow.failure.await(1, TimeUnit.SECONDS));
        assertTrue(slow.error.get() instanceof IllegalStateException);
        controller.close();
    }

    @Test
    void completionRaceAfterDisconnectDoesNotBreakTheSharedWriter() throws Exception {
        AgentService service = mock(AgentService.class);
        SseProperties properties = new SseProperties();
        properties.setWriterCoreThreads(1);
        properties.setWriterMaxThreads(1);
        AgentController controller = new AgentController(service, request -> IDENTITY, properties);
        ChatStreamEvent event =
                new ChatStreamEvent(
                        "text_delta", "one", "", "one", "running", null, null, null, null);
        CompletionRacingEmitter disconnected = new CompletionRacingEmitter();

        controller.stream(Flux.just(event), disconnected);
        assertTrue(disconnected.completionAttempted.await(1, TimeUnit.SECONDS));
        assertEquals(0, controller.streamStatus().getActiveConnections());

        RecordingEmitter recovered = new RecordingEmitter();
        controller.stream(Flux.just(event), recovered);
        assertTrue(recovered.completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, recovered.sent.get());
        controller.close();
    }

    private static final class BlockingEmitter extends SseEmitter {
        private final CountDownLatch sendStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch failure = new CountDownLatch(1);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void send(SseEventBuilder event) throws IOException {
            sendStarted.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
        }

        @Override
        public void completeWithError(Throwable failure) {
            error.set(failure);
            this.failure.countDown();
        }
    }

    private static final class CompletionRacingEmitter extends SseEmitter {
        private final CountDownLatch completionAttempted = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder event) throws IOException {
            throw new IOException("browser disconnected");
        }

        @Override
        public void completeWithError(Throwable failure) {
            completionAttempted.countDown();
            throw new IllegalStateException("async context already failed");
        }
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final AtomicInteger sent = new AtomicInteger();
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder event) {
            sent.incrementAndGet();
        }

        @Override
        public void complete() {
            completed.countDown();
        }
    }
}
