package dev.horizen.agent.web.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.application.turn.TurnServices;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent.Type;
import dev.horizen.agent.web.api.*;
import dev.horizen.agent.web.api.chat.ChatApi.ChatRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

class AgentTurnChatOutcomeTest {
    private final ExecutionIdentity identity = new ExecutionIdentity("owner", "actor");
    private final ChatRequest request = new ChatRequest("session", "hello", "request", List.of());

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void unknownFailureUsesPublicMessageAndRecordsRedactedServerDiagnostics(CapturedOutput output) {
        var services = mock(TurnServices.class);
        String detail =
                "SELECT private_column FROM private_table at /srv/private/project; credential=synthetic-private-key";
        when(services.start(any(), any()))
                .thenReturn(
                        Flux.just(event(Type.TURN_STARTED))
                                .concatWith(Flux.error(new IllegalStateException(detail))));
        try (var coordinator =
                     new AgentTurnCoordinator(
                             Duration.ofSeconds(1),
                             Optional.of(services),
                             new AgentApiMapper(false),
                             mock(AgentSessionApiService.class),
                             text -> text.replace("synthetic-private-key", "***"))) {
            var error = assertThrows(ApiException.class, () -> coordinator.chat(identity, request));
            var response = new ApiExceptionHandler().handleApiException(error);
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
            assertEquals(Map.of("error", "执行失败，请稍后重试"), response.getBody());
            assertFalse(error.getMessage().contains("SELECT"));
            assertFalse(error.getMessage().contains("/srv/private"));
            assertFalse(output.getAll().contains("synthetic-private-key"));
            assertTrue(output.getAll().contains("sessionId=session"));
            assertTrue(output.getAll().contains("requestId=request"));
            assertTrue(output.getAll().contains("turnId=turn"));
            assertTrue(output.getAll().contains("/srv/private/project"));
        }
    }

    @Test
    void synchronousFailureWithoutMessageAlsoUsesFixedResponse() {
        var services = mock(TurnServices.class);
        when(services.start(any(), any())).thenThrow(new IllegalStateException());
        try (var coordinator = coordinator(Optional.of(services))) {
            var error = assertThrows(ApiException.class, () -> coordinator.chat(identity, request));
            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
            assertEquals("执行失败，请稍后重试", error.getMessage());
        }
    }

    @Test
    void mapsFailureTimeoutCancellationAndHumanWaitToExplicitHttpOutcomes() {
        Map<Type, HttpStatus> expected =
                Map.of(
                        Type.TURN_FAILED, HttpStatus.BAD_GATEWAY,
                        Type.TURN_TIMED_OUT, HttpStatus.GATEWAY_TIMEOUT,
                        Type.TURN_CANCELLED, HttpStatus.CONFLICT,
                        Type.APPROVAL_REQUIRED, HttpStatus.CONFLICT,
                        Type.ASK_USER_REQUIRED, HttpStatus.CONFLICT);
        expected.forEach(
                (type, status) -> {
                    var services = mock(TurnServices.class);
                    when(services.start(any(), any())).thenReturn(Flux.just(event(type)));
                    try (var coordinator = coordinator(Optional.of(services))) {
                        var error =
                                assertThrows(
                                        ApiException.class,
                                        () -> coordinator.chat(identity, request));
                        assertEquals(status, error.getStatus(), type.name());
                        assertFalse(error.getMessage().contains("onNext"));
                        assertFalse(error.getMessage().contains("Flux"));
                    }
                });
    }

    @Test
    void ordinaryNoticeDoesNotHideACompletedReply() {
        var services = mock(TurnServices.class);
        var notice = event(Type.EXECUTION_NOTICE);
        notice.setDetails(Map.of("errorCode", "MODEL_CONTEXT_OVERFLOW"));
        var completed = event(Type.TURN_COMPLETED);
        completed.setText("done");
        when(services.start(any(), any())).thenReturn(Flux.just(notice, completed));
        try (var coordinator = coordinator(Optional.of(services))) {
            assertEquals("done", coordinator.chat(identity, request).getReply());
        }
    }

    @Test
    void persistenceNoticeHasExplicitResultUncertaintyResponse() {
        var services = mock(TurnServices.class);
        var notice = event(Type.EXECUTION_NOTICE);
        notice.setDetails(Map.of("errorCode", "EVENT_PERSISTENCE_FAILED"));
        when(services.start(any(), any())).thenReturn(Flux.just(notice));
        try (var coordinator = coordinator(Optional.of(services))) {
            var error = assertThrows(ApiException.class, () -> coordinator.chat(identity, request));
            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
            assertTrue(error.getMessage().contains("确认结果"));
        }
    }

    @Test
    void noRuntimeAndClosedServiceReturnUnavailableRatherThanNullPointerErrors() {
        try (var coordinator = coordinator(Optional.empty())) {
            assertEquals(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> coordinator.chat(identity, request))
                            .getStatus());
        }
        var services = mock(TurnServices.class);
        var coordinator = coordinator(Optional.of(services));
        coordinator.close();
        coordinator.close();
        verify(services, never()).close();
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ApiException.class, () -> coordinator.chat(identity, request))
                        .getStatus());
        verify(services, never()).start(any(), any());
    }

    @Test
    void emptyResultIsMappedWithoutLeakingReactiveImplementationErrors() {
        var services = mock(TurnServices.class);
        when(services.start(any(), any())).thenReturn(Flux.empty());
        try (var coordinator = coordinator(Optional.of(services))) {
            var error = assertThrows(ApiException.class, () -> coordinator.chat(identity, request));
            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
            assertTrue(error.getMessage().contains("没有返回最终结果"));
        }
    }

    private AgentRuntimeEvent event(Type type) {
        return AgentRuntimeEvent.builder().type(type).turnId("turn").sessionId("session").build();
    }

    private AgentTurnCoordinator coordinator(Optional<TurnServices> services) {
        return new AgentTurnCoordinator(
                Duration.ofSeconds(30),
                services,
                new AgentApiMapper(false),
                mock(AgentSessionApiService.class),
                text -> text);
    }
}
