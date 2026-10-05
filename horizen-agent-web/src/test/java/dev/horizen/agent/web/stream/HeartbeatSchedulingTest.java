package dev.horizen.agent.web.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.web.config.SseProperties;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;

class HeartbeatSchedulingTest {
    @Test
    void aSlowConnectionDoesNotBlockAnotherConnectionsHeartbeat() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var writers =
                new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        var properties = new SseProperties();
        properties.setHeartbeatInterval(Duration.ofMillis(20));
        var firstEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondSent = new CountDownLatch(1);
        try (var manager = new SseConnectionManager(properties, scheduler, writers)) {
            manager.open(
                    Flux.never(),
                    new SseEmitter() {
                        @Override
                        public void send(SseEventBuilder event) throws IOException {
                            firstEntered.countDown();
                            try {
                                release.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            manager.open(
                    Flux.never(),
                    new SseEmitter() {
                        @Override
                        public void send(SseEventBuilder event) {
                            secondSent.countDown();
                        }
                    });
            assertTrue(
                    secondSent.await(1, TimeUnit.SECONDS),
                    "One slow writer must not monopolize the shared heartbeat scheduler");
            release.countDown();
        } finally {
            release.countDown();
            scheduler.shutdownNow();
            writers.shutdownNow();
        }
    }
}
