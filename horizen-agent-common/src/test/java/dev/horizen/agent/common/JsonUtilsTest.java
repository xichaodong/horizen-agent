package dev.horizen.agent.common;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.SerializationFeature;

import dev.horizen.agent.common.json.JsonUtils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

class JsonUtilsTest {
    @Test
    void configuringOneCodecCannotChangeOtherCodecsOrTheDefaultWireFormat() throws Exception {
        var first = JsonUtils.newMapper();
        var second = JsonUtils.newMapper();
        first.enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        assertNotSame(first, second);
        Instant value = Instant.parse("2026-10-03T00:00:00Z");
        assertTrue(first.writeValueAsString(value).startsWith("1"));
        assertEquals("\"2026-10-03T00:00:00Z\"", second.writeValueAsString(value));
        assertEquals(second.writeValueAsString(value), JsonUtils.write(value));
    }

    @Test
    void timeSupportAndWireFormatAreAvailableWithoutLoadingAnAdapter() throws Exception {
        Instant instant = Instant.parse("2026-10-03T00:00:00Z");
        String json =
                JsonUtils.write(
                        Map.of(
                                "at",
                                instant,
                                "day",
                                LocalDate.of(2026, 10, 3),
                                "timestampMs",
                                123L));
        var value = JsonUtils.readTree(json);
        assertEquals("2026-10-03T00:00:00Z", value.path("at").asText());
        assertEquals("2026-10-03", value.path("day").asText());
        assertEquals(123L, value.path("timestampMs").asLong());
        assertTrue(value.path("timestampMs").isIntegralNumber());
        assertEquals(instant, JsonUtils.read("\"2026-10-03T00:00:00Z\"", Instant.class));
    }
}
