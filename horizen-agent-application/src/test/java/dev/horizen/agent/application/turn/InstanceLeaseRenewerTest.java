package dev.horizen.agent.application.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.horizen.agent.execution.turn.SessionTurnStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class InstanceLeaseRenewerTest {
    @Test
    void takesTurnSnapshotAfterHeartbeatDelay() throws Exception {
        SessionTurnStore store = mock(SessionTurnStore.class);
        CountDownLatch renewed = new CountDownLatch(1);
        when(store.renewLeases(any(), anyString(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            renewed.countDown();
                            return 1;
                        });
        LeaseRenewalPolicy properties = properties(50, 2, 0, Duration.ofMillis(10));
        try (InstanceLeaseRenewer renewer =
                     new InstanceLeaseRenewer(
                             store,
                             "instance",
                             Duration.ofSeconds(5),
                             Duration.ofMillis(200),
                             properties)) {
            renewer.start();
            Thread.sleep(50L);
            renewer.track("owner", "turn-started-during-wait");

            assertTrue(renewed.await(300L, TimeUnit.MILLISECONDS), "心跳等待期间启动的 Turn 应进入紧接着的一轮续租");
        }
    }

    @Test
    void boundsBatchConcurrencyAndDoesNotOverlapSlowRounds() throws Exception {
        SessionTurnStore store = mock(SessionTurnStore.class);
        AtomicInteger activeSql = new AtomicInteger();
        AtomicInteger maxActiveSql = new AtomicInteger();
        when(store.renewLeases(any(), anyString(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            int active = activeSql.incrementAndGet();
                            maxActiveSql.accumulateAndGet(active, Math::max);
                            try {
                                Thread.sleep(80L);
                                Map<String, List<String>> values = invocation.getArgument(0);
                                return values.values().stream().mapToInt(List::size).sum();
                            } finally {
                                activeSql.decrementAndGet();
                            }
                        });
        LeaseRenewalPolicy properties = properties(2, 2, 0, Duration.ofMillis(10));
        try (InstanceLeaseRenewer renewer =
                     new InstanceLeaseRenewer(
                             store,
                             "instance",
                             Duration.ofSeconds(5),
                             Duration.ofMillis(20),
                             properties)) {
            for (int index = 1; index <= 5; index++) {
                renewer.track("owner", "turn-" + index);
            }
            renewer.start();
            Thread.sleep(420L);

            InstanceLeaseRenewer.Status status = renewer.status();
            assertEquals(3, status.getLastRoundBatches());
            assertEquals(2, status.getLastBatchSize());
            assertEquals(2, status.getMaxConcurrentBatches());
            assertTrue(status.getRenewalRounds() <= 3, "慢轮次完成后才应等待并启动下一轮");
            assertTrue(maxActiveSql.get() <= 2);
        }
    }

    @Test
    void retriesOnlyFailedBatchAndLetsOtherBatchComplete() {
        SessionTurnStore store = mock(SessionTurnStore.class);
        AtomicInteger failedBatchAttempts = new AtomicInteger();
        AtomicInteger successfulBatchAttempts = new AtomicInteger();
        when(store.renewLeases(any(), anyString(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Map<String, List<String>> values = invocation.getArgument(0);
                            List<String> ids =
                                    values.values().stream().flatMap(List::stream).toList();
                            if (ids.contains("turn-1")
                                    && failedBatchAttempts.incrementAndGet() == 1) {
                                throw new IllegalStateException("temporary database failure");
                            }
                            if (!ids.contains("turn-1")) successfulBatchAttempts.incrementAndGet();
                            return ids.size();
                        });
        LeaseRenewalPolicy properties = properties(2, 2, 1, Duration.ofMillis(10));
        try (InstanceLeaseRenewer renewer =
                     new InstanceLeaseRenewer(
                             store,
                             "instance",
                             Duration.ofSeconds(5),
                             Duration.ofSeconds(1),
                             properties)) {
            for (int index = 1; index <= 4; index++) {
                renewer.track("owner", "turn-" + index);
            }

            renewer.runOnce().block(Duration.ofSeconds(2));

            assertEquals(2, failedBatchAttempts.get());
            assertEquals(1, successfulBatchAttempts.get());
            assertEquals(4, renewer.status().getRenewedTurns());
            assertEquals(0, renewer.status().getRenewalFailures());
        }
    }

    @Test
    void removesTurnThatEndsBeforeItsBatchCanRenew() {
        SessionTurnStore store = mock(SessionTurnStore.class);
        when(store.renewLeases(any(), anyString(), any(), any())).thenReturn(0);
        when(store.findTurn(anyString(), anyString())).thenReturn(Optional.empty());
        LeaseRenewalPolicy properties = properties(50, 2, 0, Duration.ofMillis(10));
        try (InstanceLeaseRenewer renewer =
                     new InstanceLeaseRenewer(
                             store,
                             "instance",
                             Duration.ofSeconds(5),
                             Duration.ofSeconds(1),
                             properties)) {
            renewer.track("owner", "turn-ended");

            renewer.runOnce().block(Duration.ofSeconds(1));

            assertEquals(0, renewer.status().getTrackedTurns());
        }
    }

    @Test
    void retryCannotRenewTurnThatEndedAfterFirstAttempt() {
        SessionTurnStore store = mock(SessionTurnStore.class);
        AtomicInteger attempts = new AtomicInteger();
        when(store.renewLeases(any(), anyString(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException("temporary database failure");
                            }
                            return 0;
                        });
        when(store.findTurn(anyString(), anyString())).thenReturn(Optional.empty());
        LeaseRenewalPolicy properties = properties(50, 2, 1, Duration.ofMillis(10));
        try (InstanceLeaseRenewer renewer =
                     new InstanceLeaseRenewer(
                             store,
                             "instance",
                             Duration.ofSeconds(5),
                             Duration.ofSeconds(1),
                             properties)) {
            renewer.track("owner", "turn-ended-during-retry");

            renewer.runOnce().block(Duration.ofSeconds(1));

            assertEquals(2, attempts.get());
            assertEquals(0, renewer.status().getTrackedTurns());
            assertEquals(0, renewer.status().getRenewedTurns());
        }
    }

    private static LeaseRenewalPolicy properties(
            int batchSize, int concurrency, int retries, Duration retryDelay) {
        return new LeaseRenewalPolicy(batchSize, concurrency, retries, retryDelay);
    }
}
