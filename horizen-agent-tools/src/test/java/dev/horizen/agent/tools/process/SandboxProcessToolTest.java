package dev.horizen.agent.tools.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

class SandboxProcessToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test
    void startsWaitsAndReadsIncrementalLogs() throws Exception {
        SandboxProcessTool tool = tool();
        JsonNode started =
                call(
                        tool,
                        Map.of(
                                "action",
                                "start",
                                "command",
                                "echo first; echo warning >&2; sleep 0.2; echo second"));
        String id = started.path("process_id").asText();
        assertTrue(id.matches("proc_[a-f0-9]{32}"));

        JsonNode waited =
                call(tool, Map.of("action", "wait", "process_id", id, "timeout_seconds", 5));
        assertEquals("exited", waited.path("status").asText());
        assertEquals(0, waited.path("exit_code").asInt());

        JsonNode logs =
                call(
                        tool,
                        Map.of(
                                "action",
                                "logs",
                                "process_id",
                                id,
                                "stdout_offset",
                                0,
                                "stderr_offset",
                                0,
                                "limit",
                                1024));
        assertTrue(logs.path("stdout").asText().contains("first"));
        assertTrue(logs.path("stdout").asText().contains("second"));
        assertTrue(logs.path("stderr").asText().contains("warning"));
        assertTrue(logs.path("stdout_next_offset").asLong() > 0);

        JsonNode listed = call(tool, Map.of("action", "list"));
        assertTrue(listed.path("process_ids").toString().contains(id));
    }

    @Test
    void sendsStdinAndTerminatesProcessGroup() throws Exception {
        SandboxProcessTool tool = tool();
        String reader =
                call(tool, Map.of("action", "start", "command", "read line; echo got:$line"))
                        .path("process_id")
                        .asText();
        call(
                tool,
                Map.of(
                        "action",
                        "stdin",
                        "process_id",
                        reader,
                        "data",
                        "hello",
                        "append_newline",
                        true));
        assertEquals(
                "exited",
                call(tool, Map.of("action", "wait", "process_id", reader, "timeout_seconds", 5))
                        .path("status")
                        .asText());
        assertTrue(
                call(tool, Map.of("action", "logs", "process_id", reader))
                        .path("stdout")
                        .asText()
                        .contains("got:hello"));

        String sleeper =
                call(tool, Map.of("action", "start", "command", "while true; do sleep 1; done"))
                        .path("process_id")
                        .asText();
        JsonNode killed =
                call(tool, Map.of("action", "kill", "process_id", sleeper, "signal", "kill"));
        assertTrue(
                Set.of("killed", "lost").contains(killed.path("status").asText()),
                killed.toString());
    }

    @Test
    void rejectsHostExecutionAndUnsafeIdentifiers() throws Exception {
        SandboxProcessTool production = new SandboxProcessTool();
        ToolResultBlock missingSandbox =
                rawCall(production, Map.of("action", "start", "command", "echo no"));
        assertTrue(text(missingSandbox).contains("active sandbox"));
        assertTrue(
                text(rawCall(tool(), Map.of("action", "status", "process_id", "../../etc/passwd")))
                        .contains("invalid process_id"));
    }

    private SandboxProcessTool tool() {
        return new SandboxProcessTool(
                (context, command, timeout) -> {
                    Path bin = workspace.resolve("test-bin");
                    Files.createDirectories(bin);
                    Path setsid = bin.resolve("setsid");
                    if (!Files.exists(setsid)) {
                        Files.writeString(
                                setsid,
                                """
                #!/usr/bin/env python3
                import os, sys
                os.setsid()
                os.execvp(sys.argv[1], sys.argv[1:])
                """);
                        setsid.toFile().setExecutable(true);
                    }
                    ProcessBuilder builder =
                            new ProcessBuilder("bash", "-lc", command)
                                    .directory(workspace.toFile());
                    builder.environment()
                            .put("PATH", bin + ":" + builder.environment().get("PATH"));
                    Process process = builder.start();
                    boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
                    if (!completed) {
                        process.destroyForcibly();
                        return new ExecuteResponse("test command timed out", 124, false);
                    }
                    String stdout =
                            new String(
                                    process.getInputStream().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String stderr =
                            new String(
                                    process.getErrorStream().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    int exit = process.exitValue();
                    return new ExecuteResponse(exit == 0 ? stdout : stdout + stderr, exit, false);
                });
    }

    private static JsonNode call(SandboxProcessTool tool, Map<String, Object> input)
            throws Exception {
        ToolResultBlock result = rawCall(tool, input);
        String text = text(result);
        if (text.startsWith("Error: ")) throw new AssertionError(text);
        return JSON.readTree(text);
    }

    private static ToolResultBlock rawCall(SandboxProcessTool tool, Map<String, Object> input) {
        return tool.callAsync(
                        ToolCallParam.builder()
                                .runtimeContext(
                                        RuntimeContext.builder()
                                                .userId("owner")
                                                .sessionId("session")
                                                .build())
                                .input(input)
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("call")
                                                .name("process")
                                                .input(input)
                                                .content("{}")
                                                .build())
                                .build())
                .block(Duration.ofSeconds(15));
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
