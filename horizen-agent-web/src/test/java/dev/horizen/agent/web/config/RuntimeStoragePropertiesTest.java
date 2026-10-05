package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class RuntimeStoragePropertiesTest {

    @Test
    void localModeDoesNotRequireExternalStorage() {
        RuntimeStorageProperties properties =
                new RuntimeStorageProperties(
                        null, null, null, null, 0, 0, null, null, null, null, null, null, null,
                        null);

        assertEquals(RuntimeStorageProperties.Mode.LOCAL, properties.getMode());
        assertEquals(Duration.ofSeconds(30), properties.getLeaseTtl());
        assertEquals(Duration.ofMinutes(15), properties.getEventTtl());
        assertEquals(Duration.ofHours(24), properties.getSessionStateTtl());
        assertEquals(8, properties.getRedisMaximumPoolSize());
    }

    @Test
    void distributedModeRequiresBothMysqlAndRedis() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuntimeStorageProperties(
                                RuntimeStorageProperties.Mode.DISTRIBUTED,
                                "",
                                "",
                                "",
                                10,
                                8,
                                "redis://127.0.0.1:6379",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuntimeStorageProperties(
                                RuntimeStorageProperties.Mode.DISTRIBUTED,
                                "jdbc:mysql://127.0.0.1/test",
                                "",
                                "",
                                10,
                                8,
                                "",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void sessionStateTtlMustCoverHumanInteractionWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuntimeStorageProperties(
                                RuntimeStorageProperties.Mode.LOCAL,
                                "",
                                "",
                                "",
                                10,
                                8,
                                "",
                                null,
                                null,
                                Duration.ofMinutes(15),
                                Duration.ofMinutes(30),
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(1)));
    }
}
