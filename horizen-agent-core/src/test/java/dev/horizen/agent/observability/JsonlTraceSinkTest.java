package dev.horizen.agent.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

class JsonlTraceSinkTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesAppendOnlyJsonWithNestedSecretRedaction() throws Exception {
        Path file = directory.resolve("nested/trace.jsonl");
        Map<String, Object> payload =
                Map.of(
                        "headers",
                        Map.of("Authorization", "private-value"),
                        "items",
                        List.of(Map.of("api_key", "secret-value", "text", "第一行\nsecond line")));
        try (var sink = new JsonlTraceSink(file)) {
            sink.record(event("tool.result", payload));
        }
        try (var sink = new JsonlTraceSink(file)) {
            sink.record(event("turn.finished", Map.of()));
        }
        var lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        var json = mapper.readTree(lines.get(0));
        assertEquals("turn-1", json.path("turnId").asText());
        assertEquals("[REDACTED]", json.at("/attributes/headers/Authorization").asText());
        assertEquals("[REDACTED]", json.at("/attributes/items/0/api_key").asText());
        assertEquals("第一行\nsecond line", json.at("/attributes/items/0/text").asText());
        assertEquals("private-value", ((Map<?, ?>) payload.get("headers")).get("Authorization"));
        assertFalse(Files.readString(file).contains("secret-value"));
    }

    @Test
    void concurrentWritersDoNotInterleaveJsonLines() throws Exception {
        Path file = directory.resolve("parallel.jsonl");
        try (var sink = new JsonlTraceSink(file)) {
            var executor = Executors.newFixedThreadPool(4);
            try {
                var futures =
                        IntStream.range(0, 40)
                                .mapToObj(
                                        i ->
                                                executor.submit(
                                                        () ->
                                                                sink.record(
                                                                        event(
                                                                                "event",
                                                                                Map.of(
                                                                                        "index",
                                                                                        i)))))
                                .toList();
                for (var future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }
        }
        var lines = Files.readAllLines(file);
        assertEquals(40, lines.size());
        var values = new HashSet<Integer>();
        for (String line : lines) {
            values.add(mapper.readTree(line).at("/attributes/index").asInt());
        }
        assertEquals(40, values.size());
    }

    @Test
    void requiresCorrelationIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TraceEvent.now(
                                "", "session", "trace", "span", null, "turn.started", Map.of()));
    }

    private static TraceEvent event(String type, Map<String, Object> attributes) {
        return TraceEvent.now("turn-1", "session-1", "trace-1", "span-1", null, type, attributes);
    }
}
