package dev.horizen.agent.web.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.application.turn.TurnControlChannel.CancelSignal;

import io.agentscope.harness.agent.bus.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

class MessageBusTurnControlChannelTest {
    @Test
    void malformedMessageCannotDiscardValidMessagesInTheSameDrain() {
        MessageBus bus = mock(MessageBus.class);
        when(bus.queueDrain(anyString(), anyInt()))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new BusEntry("1", payload("first")),
                                        new BusEntry(
                                                "2", Map.of("type", "cancel", "ownerKey", "owner")),
                                        new BusEntry("3", Map.of("type", "other")),
                                        new BusEntry("4", payload("last")))));
        var channel = new MessageBusTurnControlChannel(bus, Duration.ofSeconds(1));
        assertEquals(
                List.of(
                        new CancelSignal("owner", "session", "first"),
                        new CancelSignal("owner", "session", "last")),
                channel.drain("instance", 100).block());
        verify(bus, times(1)).queueDrain("horizen:control:instance", 100);
    }

    @Test
    void anEmptySendAcknowledgementIsNotReportedAsSuccess() {
        MessageBus bus = mock(MessageBus.class);
        when(bus.queuePush(anyString(), any())).thenReturn(Mono.empty());
        var channel = new MessageBusTurnControlChannel(bus, Duration.ofSeconds(1));
        assertThrows(
                IllegalStateException.class,
                () -> channel.send("instance", new CancelSignal("owner", "session", "turn")));
    }

    private Map<String, Object> payload(String turn) {
        return Map.of(
                "type", "cancel", "ownerKey", "owner", "sessionId", "session", "turnId", turn);
    }
}
