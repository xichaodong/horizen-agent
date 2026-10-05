package dev.horizen.agent.storage.redis;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.SignalType;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class RedisSubscriptionTest {
    @Test
    void deliversDeferredDemandWithoutWaitingForBlockingSubscribeToReturn() throws Exception {
        try (var commands = new JedisPooled("127.0.0.1", 1);
                var pool = new FakePool()) {
            var subscriber = new ManualSubscriber();
            bus(commands, pool).subscribe("turn").subscribe(subscriber);
            assertTrue(pool.connection.ready.await(2, TimeUnit.SECONDS));
            pool.connection.emit(1);
            pool.connection.emit(2);
            assertTrue(subscriber.values.isEmpty());
            subscriber.request(2);
            assertTrue(subscriber.received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), subscriber.values);
            subscriber.cancel();
            assertTrue(pool.connection.closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, pool.connection.closes.get());
        }
    }

    @Test
    void overflowClosesTheConnectionAndRetainsOnlyTheBoundedBacklog() throws Exception {
        try (var commands = new JedisPooled("127.0.0.1", 1);
                var pool = new FakePool()) {
            var subscriber = new ManualSubscriber();
            bus(commands, pool).subscribe("turn").subscribe(subscriber);
            assertTrue(pool.connection.ready.await(2, TimeUnit.SECONDS));
            pool.connection.emit(1);
            pool.connection.emit(2);
            pool.connection.emit(3);
            assertTrue(pool.connection.closed.await(2, TimeUnit.SECONDS));
            subscriber.request(Long.MAX_VALUE);
            assertTrue(subscriber.terminated.await(2, TimeUnit.SECONDS));
            assertNotNull(subscriber.error);
            assertEquals(List.of(1, 2), subscriber.values);
            subscriber.cancel();
            assertEquals(1, pool.connection.closes.get());
        }
    }

    private RedisMessageBus bus(JedisPooled commands, FakePool pool) {
        return new RedisMessageBus(commands, pool, "test:", Duration.ofMinutes(1), 2);
    }

    private static final class ManualSubscriber extends BaseSubscriber<Map<String, Object>> {
        final List<Integer> values = new CopyOnWriteArrayList<>();
        final CountDownLatch received = new CountDownLatch(2);
        final CountDownLatch terminated = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        protected void hookOnSubscribe(Subscription subscription) {}

        @Override
        protected void hookOnNext(Map<String, Object> value) {
            values.add(((Number) value.get("value")).intValue());
            received.countDown();
        }

        @Override
        protected void hookOnError(Throwable error) {
            this.error = error;
        }

        @Override
        protected void hookFinally(SignalType signal) {
            terminated.countDown();
        }
    }

    private static final class FakePool extends JedisPool {
        final FakeConnection connection = new FakeConnection();

        @Override
        public Jedis getResource() {
            return connection;
        }

        @Override
        public void close() {
            connection.close();
            super.close();
        }
    }

    private static final class FakeConnection extends Jedis {
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        volatile JedisPubSub listener;

        @Override
        public void subscribe(JedisPubSub listener, String... channels) {
            this.listener = listener;
            ready.countDown();
            try {
                closed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        void emit(int value) {
            listener.onMessage("turn", "{\"value\":" + value + "}");
        }

        @Override
        public void close() {
            if (closed.getCount() > 0) {
                closes.incrementAndGet();
                closed.countDown();
            }
        }
    }
}
