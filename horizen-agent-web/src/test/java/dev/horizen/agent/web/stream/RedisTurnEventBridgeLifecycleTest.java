package dev.horizen.agent.web.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class RedisTurnEventBridgeLifecycleTest {
    @Test
    void closeCancelsActiveReplayReadAndPreventsNewPollers() throws Exception {
        MessageBus bus = mock(MessageBus.class);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(bus.logRead(anyString(), isNull(), anyInt()))
                .thenReturn(
                        Mono.defer(
                                () -> {
                                    reading.countDown();
                                    return Mono.<List<BusEntry>>never()
                                            .doOnCancel(cancelled::countDown);
                                }));
        var bridge = new RedisTurnEventBridge(bus);
        var subscription =
                bridge.replay("owner", "turn")
                        .doFinally(ignored -> finished.countDown())
                        .subscribe();
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            assertEquals(1, bridge.status().getActivePollers());
            bridge.close();
            bridge.close();
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(0, bridge.status().getActivePollers());
            assertThrows(
                    IllegalStateException.class,
                    () -> bridge.replay("owner", "turn").blockFirst(Duration.ofSeconds(1)));
            assertEquals(0, bridge.status().getActivePollers());
            verify(bus, times(1)).logRead(anyString(), isNull(), anyInt());
        } finally {
            subscription.dispose();
            bridge.close();
        }
    }
}
