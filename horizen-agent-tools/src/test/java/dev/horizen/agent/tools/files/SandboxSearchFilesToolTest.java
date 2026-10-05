package dev.horizen.agent.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class SandboxSearchFilesToolTest {
    @TempDir Path workspace;

    @Test
    void invalidRegexAndMissingDirectoryRemainErrorsButNoMatchSucceeds() throws Exception {
        Files.writeString(workspace.resolve("example.txt"), "synthetic value\n");
        Assumptions.assumeTrue(
                new ProcessBuilder("bash", "-c", "command -v rg").start().waitFor() == 0);
        assertEquals(2, run("[", "."));
        assertEquals(2, run("synthetic", "missing-directory"));
        assertEquals(0, run("absent", "."));
        assertEquals(0, run("synthetic", "."));
    }

    private int run(String pattern, String path) throws Exception {
        var param =
                ToolCallParam.builder()
                        .input(Map.of("pattern", pattern, "path", path))
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("search-test")
                                        .name("search_files")
                                        .input(Map.of("pattern", pattern, "path", path))
                                        .build())
                        .build();
        var process =
                new ProcessBuilder("bash", "-c", SandboxSearchFilesTool.command(param))
                        .directory(workspace.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        Assertions.assertTrue(process.waitFor(3, TimeUnit.SECONDS));
        return process.exitValue();
    }

    @Test
    void normalizesWorkspacePath() {
        assertEquals("src/main", SandboxSearchFilesTool.safePath("src/./main"));
        assertEquals(".", SandboxSearchFilesTool.safePath("."));
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThrows(
                IllegalArgumentException.class, () -> SandboxSearchFilesTool.safePath("../secret"));
        assertThrows(IllegalArgumentException.class, () -> SandboxSearchFilesTool.safePath("/etc"));
    }
}
