package dev.horizen.agent.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.adapter.agentscope.workspace.document.ReadOnlyAgentsMdBaseStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Mono;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EnabledIfSystemProperty(named = "horizen.redis.live", matches = "true")
class RedisAgentRuntimeStoreLiveTest {

    @TempDir
    Path workspace;

    private JedisPooled commands;
    private JedisPool subscriptions;
    private RedisAgentRuntimeStore store;
    private String testPrefix;

    @BeforeEach
    void setUp() {
        URI uri = URI.create(System.getProperty("horizen.redis.url", "redis://127.0.0.1:6379"));
        commands = new JedisPooled(uri);
        subscriptions = new JedisPool(uri);
        testPrefix = "horizen-test:" + UUID.randomUUID() + ":";
        store =
                new RedisAgentRuntimeStore(
                        commands, subscriptions, testPrefix, Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        if (commands != null) {
            Set<String> keys = commands.keys(testPrefix + "*");
            if (!keys.isEmpty()) {
                commands.del(keys.toArray(String[]::new));
            }
            commands.close();
        }
        if (subscriptions != null) {
            subscriptions.close();
        }
    }

    @Test
    void officialStateStoreSupportsVersionedCrossInstanceState() {
        var stateStore = store.agentStateStore();
        stateStore.save("owner", "session", "state", new SampleState("one"));
        VersionedState<SampleState> current =
                stateStore.getVersioned("owner", "session", "state", SampleState.class);

        assertTrue(current.isPresent());
        assertEquals("one", current.value().value());
        assertTtl("session:owner/session:state");
        assertTtl("session:owner/session:state:ver");
        assertTtl("session:owner/session:_keys");
        long nextVersion =
                stateStore.saveIfVersion(
                        "owner", "session", "state", new SampleState("two"), current.version());
        assertTrue(nextVersion > current.version());
    }

    @Test
    void sessionStateExpiresAfterConfiguredInactivity() throws InterruptedException {
        var delegate =
                RedisAgentStateStore.builder()
                        .jedisClient(commands)
                        .keyPrefix(testPrefix + "session:")
                        .build();
        var expiring =
                new ExpiringAgentStateStore(delegate, commands, testPrefix, Duration.ofSeconds(1));
        expiring.save("owner", "expiring-session", "state", new SampleState("one"));
        String stateKey = testPrefix + "session:owner/expiring-session:state";
        assertTrue(commands.ttl(stateKey) > 0);

        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (commands.exists(stateKey) && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue(!commands.exists(stateKey), "state did not expire");
        assertTrue(expiring.get("owner", "expiring-session", "state", SampleState.class).isEmpty());
    }

    private void assertTtl(String suffix) {
        long ttl = commands.ttl(testPrefix + suffix);
        assertTrue(ttl > 0 && ttl <= Duration.ofHours(24).toSeconds(), suffix + " ttl=" + ttl);
    }

    @Test
    void messageBusSupportsQueueReplayAndLiveBroadcast() {
        var bus = store.messageBus();

        bus.queuePush("control:instance-a", Map.of("type", "cancel")).block();
        List<BusEntry> controls = bus.queueDrain("control:instance-a", 10).block();
        assertEquals("cancel", controls.get(0).payload().get("type"));
        assertTrue(bus.queueDrain("control:instance-a", 10).block().isEmpty());

        String first = bus.logAppend("turn:events", Map.of("type", "start"), 100).block();
        bus.logAppend("turn:events", Map.of("type", "delta"), 100).block();
        List<BusEntry> replay = bus.logRead("turn:events", first, 10).block();
        assertEquals(1, replay.size());
        assertEquals("delta", replay.get(0).payload().get("type"));
        commands.del(testPrefix + "bus:log-head-sequence:turn:events");
        assertEquals(
                "delta",
                bus.logRead("turn:events", first, 10).block().get(0).payload().get("type"));

        Mono<Map<String, Object>> live = bus.subscribe("turn:live").next();
        Mono.delay(Duration.ofMillis(200))
                .then(bus.publish("turn:live", Map.of("type", "done")))
                .subscribe();
        Map<String, Object> delivered = live.timeout(Duration.ofSeconds(3)).block();
        assertEquals("done", delivered.get("type"));
    }

    @Test
    void boundedLogDeduplicatesRetriedWriteAndRejectsExcessBytes() {
        RedisMessageBus bus = (RedisMessageBus) store.messageBus();
        Map<String, Object> event =
                Map.of("_write_id", "same-write", "type", "delta", "text", "hello");

        String first = bus.logAppendBounded("bounded-turn", event, 0, 1024, 4096).block();
        String retry = bus.logAppendBounded("bounded-turn", event, 0, 1024, 4096).block();

        assertEquals(first, retry);
        assertEquals(1, bus.logRead("bounded-turn", null, 10).block().size());
        Assertions.assertThrows(
                RuntimeException.class,
                () ->
                        bus.logAppendBounded(
                                        "bounded-turn",
                                        Map.of(
                                                "_write_id",
                                                "large-write",
                                                "text",
                                                "x".repeat(2048)),
                                        0,
                                        128,
                                        4096)
                                .block());
        Assertions.assertThrows(
                RuntimeException.class,
                () ->
                        bus.logAppendBounded(
                                        "event-limited-turn",
                                        Map.of("_write_id", "first-limited", "text", "first"),
                                        0,
                                        1024,
                                        4096,
                                        1)
                                .then(
                                        bus.logAppendBounded(
                                                "event-limited-turn",
                                                Map.of("_write_id", "second-write", "text", "next"),
                                                0,
                                                1024,
                                                4096,
                                                1))
                                .block());
        assertEquals(1, bus.logRead("bounded-turn", null, 10).block().size());
        assertEquals(1, bus.logRead("event-limited-turn", null, 10).block().size());

        bus.logAppendBounded(
                        "metadata-turn",
                        Map.of("_write_id", "metadata-one", "text", "one"),
                        0,
                        1024,
                        4096)
                .block();
        commands.del(testPrefix + "bus:log-bytes:metadata-turn");
        Assertions.assertThrows(
                RuntimeException.class,
                () ->
                        bus.logAppendBounded(
                                        "metadata-turn",
                                        Map.of("_write_id", "metadata-two", "text", "two"),
                                        0,
                                        1024,
                                        4096)
                                .block());
    }

    @Test
    void remoteWorkspaceSharesPublishedAgentsMdAcrossInstancesAndOwnersStayIsolated() {
        var publisherFilesystem =
                new RemoteFilesystemSpec(store.baseStore())
                        .isolationScope(IsolationScope.USER)
                        .toFilesystem(
                                workspace,
                                "horizen-web-agent",
                                IsolationScope.USER.toNamespaceFactory());
        var secondInstanceFilesystem =
                new RemoteFilesystemSpec(new ReadOnlyAgentsMdBaseStore(store.baseStore()))
                        .isolationScope(IsolationScope.USER)
                        .toFilesystem(
                                workspace,
                                "horizen-web-agent",
                                IsolationScope.USER.toNamespaceFactory());
        RuntimeContext ownerA =
                RuntimeContext.builder().userId("redis-owner-a").sessionId("same-session").build();
        RuntimeContext ownerB =
                RuntimeContext.builder().userId("redis-owner-b").sessionId("same-session").build();

        publisherFilesystem.write(ownerA, "AGENTS.md", "redis-instruction-a");
        publisherFilesystem.write(ownerB, "AGENTS.md", "redis-instruction-b");

        assertEquals(
                "redis-instruction-a",
                secondInstanceFilesystem.read(ownerA, "AGENTS.md", 0, 0).fileData().content());
        assertEquals(
                "redis-instruction-b",
                secondInstanceFilesystem.read(ownerB, "AGENTS.md", 0, 0).fileData().content());
        assertTrue(
                !secondInstanceFilesystem
                        .edit(ownerA, "AGENTS.md", "redis-instruction-a", "changed", false)
                        .isSuccess());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class SampleState implements State {
        private String value;

        public String value() {
            return value;
        }
    }
}
