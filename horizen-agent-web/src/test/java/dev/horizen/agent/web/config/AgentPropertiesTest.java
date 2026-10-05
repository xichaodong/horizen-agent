package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class AgentPropertiesTest {
    @Test
    void defaultsToTenMinuteRunAndTwoMinuteIdleTimeouts() {
        AgentProperties properties = new AgentProperties(null, null, null, null, null, null, null);

        assertEquals(10, properties.getMaxIters());
        assertEquals(Duration.ofMinutes(10), properties.getStreamTimeout());
        assertEquals(Duration.ofMinutes(2), properties.getIdleTimeout());
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AgentProperties(
                                "key",
                                null,
                                null,
                                null,
                                null,
                                Duration.ZERO,
                                Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AgentProperties(
                                "key",
                                null,
                                null,
                                null,
                                null,
                                Duration.ofSeconds(1),
                                Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AgentProperties(
                                "key",
                                null,
                                null,
                                null,
                                0,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(1)));
    }

    @Test
    void scriptedModeIsReadyWithoutExternalModelCredential() {
        AgentProperties properties =
                new AgentProperties(
                        null, null, null, AgentProperties.ModelMode.SCRIPTED, null, null, null);
        assertTrue(properties.ready());
    }
}
