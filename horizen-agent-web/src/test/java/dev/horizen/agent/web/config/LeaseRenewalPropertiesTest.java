package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class LeaseRenewalPropertiesTest {
    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> new LeaseRenewalProperties().validate());
    }

    @Test
    void rejectsUnboundedBatchConcurrency() {
        LeaseRenewalProperties properties = new LeaseRenewalProperties();
        properties.setConcurrency(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void rejectsConnectionAcquireTimeoutBelowHikariMinimum() {
        LeaseRenewalProperties properties = new LeaseRenewalProperties();
        properties.setConnectionAcquireTimeout(Duration.ofMillis(100));
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
