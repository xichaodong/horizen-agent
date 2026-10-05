package dev.horizen.agent.application.turn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.runtime.api.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class TurnManagerLifecycleTest {
    @Test
    void closeCompletesSubscribersCancelsSourceAndRejectsNewTurns() throws Exception {
        var manager = new TurnExecutionManager(mock(AgentRuntime.class));
        var completed = new CountDownLatch(1);
        var cancelled = new AtomicInteger();
        var events =
                manager.start(
                        request("turn"), source("turn", cancelled), Duration.ofHours(1), null);
        var observer = events.subscribe(e -> {}, e -> {}, completed::countDown);
        manager.close();
        manager.close();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, cancelled.get());
        assertFalse(manager.hasActiveTurn("owner", "session"));
        assertThrows(
                TurnExecutionManager.ClosedException.class,
                () ->
                        manager.start(
                                request("new-turn"),
                                source("new-turn", cancelled),
                                Duration.ofHours(1),
                                null));
        assertThrows(
                TurnExecutionManager.ClosedException.class,
                () ->
                        manager.subscribe("owner", "session", "turn")
                                .blockLast(Duration.ofSeconds(1)));
        observer.dispose();
    }

    @Test
    void concurrentStartAndCloseCannotLeaveLiveTurnAfterShutdown() throws Exception {
        var manager = new TurnExecutionManager(mock(AgentRuntime.class));
        var barrier = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var start =
                    workers.submit(
                            () -> {
                                barrier.await();
                                try {
                                    return manager.start(
                                            request("turn"),
                                            source("turn", new AtomicInteger()),
                                            Duration.ofHours(1),
                                            null);
                                } catch (TurnExecutionManager.ClosedException closed) {
                                    return Flux.<AgentRuntimeEvent>empty();
                                }
                            });
            var close =
                    workers.submit(
                            () -> {
                                barrier.await();
                                manager.close();
                                return true;
                            });
            close.get(3, TimeUnit.SECONDS);
            start.get(3, TimeUnit.SECONDS).collectList().block(Duration.ofSeconds(1));
            assertFalse(manager.hasActiveTurn("owner", "session"));
            assertThrows(
                    TurnExecutionManager.ClosedException.class,
                    () ->
                            manager.start(
                                    request("again"),
                                    source("again", new AtomicInteger()),
                                    Duration.ofHours(1),
                                    null));
        } finally {
            manager.close();
            workers.shutdownNow();
        }
    }

    private AgentTurnRequest request(String turn) {
        return AgentTurnRequest.builder()
                .ownerKey("owner")
                .sessionId("session")
                .turnId(turn)
                .message("hello")
                .build();
    }

    private Flux<AgentRuntimeEvent> source(String turn, AtomicInteger cancelled) {
        return Flux.concat(
                        Flux.just(
                                AgentRuntimeEvent.builder()
                                        .type(AgentRuntimeEvent.Type.TURN_STARTED)
                                        .turnId(turn)
                                        .sessionId("session")
                                        .build()),
                        Flux.<AgentRuntimeEvent>never())
                .doOnCancel(cancelled::incrementAndGet);
    }
}
