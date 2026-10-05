package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TurnEventPropertiesTest {
    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> new TurnEventProperties().validate());
    }

    @Test
    void rejectsTurnLimitBelowSingleEventLimit() {
        TurnEventProperties properties = new TurnEventProperties();
        properties.setMaxTurnBytes(1);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
