package dev.horizen.agent.web.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import io.agentscope.harness.agent.bus.MessageBus;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

class RedisTurnEventBridgeTest {
    @Test
    void retriesTransientRedisWriteFailureBeforePublishing() {
        MessageBus bus = mock(MessageBus.class);
        AtomicInteger attempts = new AtomicInteger();
        when(bus.logAppend(anyString(), any(), anyInt()))
                .thenAnswer(
                        ignored -> {
                            if (attempts.incrementAndGet() < 3) {
                                return Mono.error(new IllegalStateException("redis unavailable"));
                            }
                            return Mono.just("1");
                        });
        RedisTurnEventBridge bridge = new RedisTurnEventBridge(bus);

        long sequence = bridge.publish("owner", event());

        assertEquals(1L, sequence);
        assertEquals(3, attempts.get());
    }

    @Test
    void surfacesRedisWriteFailureAfterRetryBudgetIsExhausted() {
        MessageBus bus = mock(MessageBus.class);
        AtomicInteger attempts = new AtomicInteger();
        when(bus.logAppend(anyString(), any(), anyInt()))
                .thenAnswer(
                        ignored -> {
                            attempts.incrementAndGet();
                            return Mono.error(new IllegalStateException("redis unavailable"));
                        });
        RedisTurnEventBridge bridge = new RedisTurnEventBridge(bus);

        assertThrows(
                RedisTurnEventBridge.EventWriteException.class,
                () -> bridge.publish("owner", event()));
        assertEquals(3, attempts.get());
    }

    private static AgentRuntimeEvent event() {
        return new AgentRuntimeEvent(
                AgentRuntimeEvent.Type.TEXT_DELTA,
                "turn",
                "session",
                "event",
                "reply",
                "text",
                "running",
                null,
                null,
                null,
                null);
    }
}
