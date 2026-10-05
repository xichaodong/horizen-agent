package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SsePropertiesTest {
    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> new SseProperties().validate());
    }

    @Test
    void rejectsInvalidResourceLimits() {
        SseProperties properties = new SseProperties();
        properties.setOutboundMaxBytes(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
