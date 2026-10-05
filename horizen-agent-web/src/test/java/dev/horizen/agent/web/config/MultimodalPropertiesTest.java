package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MultimodalPropertiesTest {
    @Test
    void appliesSafeDefaults() {
        MultimodalProperties properties = new MultimodalProperties(null, null, null, null, null);
        assertTrue(properties.isDirectImageInputEnabled());
        assertEquals(5, properties.getMaxImagesPerTurn());
        assertEquals(10L * 1024 * 1024, properties.getMaxImageBytes());
        assertEquals(20L * 1024 * 1024, properties.getMaxTotalImageBytes());
        assertEquals(3600, properties.getImageUrlExpiresSeconds());
    }

    @Test
    void rejectsInvalidLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MultimodalProperties(true, 0, 1L, 1L, 3600));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MultimodalProperties(true, 1, 10L, 9L, 3600));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MultimodalProperties(true, 1, 10L, 10L, 10));
    }
}
