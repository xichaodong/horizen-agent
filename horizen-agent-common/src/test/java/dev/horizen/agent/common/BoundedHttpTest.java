package dev.horizen.agent.common;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.http.BoundedBodyHandlers;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

class BoundedHttpTest {
    @Test
    void exactLimitCompletesWithoutTruncatingContent() throws Exception {
        var subscriber = BoundedBodyHandlers.bytes(3).apply(null);
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(subscription(cancelled));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {3})));
        subscriber.onComplete();
        assertArrayEquals(new byte[] {1, 2, 3}, subscriber.getBody().toCompletableFuture().get());
        assertFalse(cancelled.get());
    }

    @Test
    void streamedOversizeCancelsTransportAndNeverReturnsPartialBody() {
        var subscriber = BoundedBodyHandlers.bytes(3).apply(null);
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(subscription(cancelled));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {3, 4})));
        subscriber.onComplete();
        assertTrue(cancelled.get());
        assertThrows(
                ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get());
    }

    private Flow.Subscription subscription(AtomicBoolean cancelled) {
        return new Flow.Subscription() {
            public void request(long count) {}

            public void cancel() {
                cancelled.set(true);
            }
        };
    }
}
