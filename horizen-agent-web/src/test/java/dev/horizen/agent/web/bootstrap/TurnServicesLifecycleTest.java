package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.application.turn.TurnServices;
import dev.horizen.agent.observability.JsonlTraceSink;
import dev.horizen.agent.runtime.api.AgentRuntime;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

class TurnServicesLifecycleTest {
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final JsonlTraceSink trace = mock(JsonlTraceSink.class);
    private final TurnServices services = mock(TurnServices.class);

    @Test
    void startsAfterSingletonsAndStopsBeforeBorrowedResourcesAreDestroyed() throws Exception {
        AtomicBoolean initialized = new AtomicBoolean();
        doAnswer(
                invocation -> {
                    assertTrue(
                            initialized.get(),
                            "Background tasks must wait for singleton initialization");
                    return null;
                })
                .when(services)
                .startRecovery();
        var context = context();
        context.registerBean(
                "lastSingleton",
                Object.class,
                () -> {
                    initialized.set(true);
                    return new Object();
                });
        verify(services, never()).startRecovery();
        try {
            context.refresh();
            assertTrue(context.getBean(TurnServicesLifecycle.class).isRunning());
            verify(services, times(1)).startRecovery();
            verify(runtime, never()).close();
            verify(trace, never()).close();
        } finally {
            context.close();
        }
        context.close();
        var order = inOrder(services, trace, runtime);
        order.verify(services).close();
        order.verify(trace).close();
        order.verify(runtime).close();
        verify(services, times(1)).close();
        verify(trace, times(1)).close();
        verify(runtime, times(1)).close();
    }

    @Test
    void failedContextInitializationNeverStartsRecoveryAndStillClosesResources() throws Exception {
        var context = context();
        context.registerBean(
                "brokenSingleton",
                Object.class,
                () -> {
                    throw new IllegalStateException("synthetic startup failure");
                });
        try {
            assertThrows(RuntimeException.class, context::refresh);
        } finally {
            context.close();
        }
        verify(services, never()).startRecovery();
        verify(services, times(1)).close();
        verify(runtime, times(1)).close();
        verify(trace, times(1)).close();
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(
                "agentRuntime",
                AgentRuntime.class,
                () -> runtime,
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(
                "traceSink",
                JsonlTraceSink.class,
                () -> trace,
                definition -> {
                    definition.setDependsOn("agentRuntime");
                    definition.setDestroyMethodName("close");
                });
        context.registerBean(
                "turnServicesLifecycle",
                TurnServicesLifecycle.class,
                () -> new TurnServicesLifecycle(Optional.of(services)),
                definition -> {
                    definition.setDependsOn("agentRuntime", "traceSink");
                    definition.setDestroyMethodName("close");
                });
        return context;
    }
}
