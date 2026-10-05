package dev.horizen.agent.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

class ObservabilityDemoTest {
    @TempDir Path directory;

    @Test
    void realAgentLoopCallsToolThenProducesCorrelatedTrace() throws Exception {
        Path file = directory.resolve("success.jsonl");
        ObservabilityDemo.main(new String[] {file.toString(), "no-horizen"});
        var events = read(file);
        assertEquals("AGENT_START", events.get(0).path("type").asText());
        assertEquals(
                2,
                events.stream()
                        .filter(e -> e.path("type").asText().equals("MODEL_CALL_START"))
                        .count());
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.path("type").asText().equals("TOOL_RESULT_END")
                                                && e.at("/attributes/toolCallId")
                                                        .asText()
                                                        .equals("demo-echo-1")
                                                && e.at("/attributes/toolState")
                                                        .asText()
                                                        .equals("success")));
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.path("type").asText().equals("AGENT_RESULT")
                                                && e.at("/attributes/text")
                                                        .asText()
                                                        .contains("Hello, Horizen!")));
        assertEquals(1, events.stream().map(e -> e.path("traceId").asText()).distinct().count());
        assertFalse(
                events.stream().anyMatch(e -> e.path("type").asText().equals("EXECUTION_ERROR")));
    }

    @Test
    void modelFailureIsRecordedAndDoesNotBecomeAnAgentResult() throws Exception {
        Path file = directory.resolve("failure.jsonl");
        assertThrows(
                RuntimeException.class,
                () -> ObservabilityDemo.main(new String[] {file.toString(), "fail", "no-horizen"}));
        var events = read(file);
        assertTrue(
                events.stream().anyMatch(e -> e.path("type").asText().equals("EXECUTION_ERROR")));
        assertFalse(events.stream().anyMatch(e -> e.path("type").asText().equals("AGENT_RESULT")));
    }

    private static ArrayList<JsonNode> read(Path file) throws Exception {
        var mapper = new ObjectMapper();
        var result = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(file)) {
            result.add(mapper.readTree(line));
        }
        return result;
    }
}
