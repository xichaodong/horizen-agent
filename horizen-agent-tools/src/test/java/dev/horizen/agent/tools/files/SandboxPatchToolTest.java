package dev.horizen.agent.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class SandboxPatchToolTest {
    @TempDir
    Path workspace;

    @Test
    void parsesV4aOperations() {
        var operations =
                V4aPatchEngine.parse(
                        """
                                *** Begin Patch
                                *** Update File: src/a.txt
                                @@ heading @@
                                 heading
                                -old
                                +new
                                *** Add File: src/new.txt
                                +hello
                                *** Move File: src/old.txt -> src/moved.txt
                                *** Delete File: src/delete.txt
                                *** End Patch
                                """);
        assertEquals(4, operations.size());
    }

    @Test
    void validatesReplaceContract() {
        SandboxPatchTool.validateInput(
                Map.of(
                        "mode",
                        "replace",
                        "path",
                        "src/a.txt",
                        "old_string",
                        "old",
                        "new_string",
                        "new"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SandboxPatchTool.validateInput(
                                Map.of(
                                        "mode",
                                        "replace",
                                        "path",
                                        "../secret",
                                        "old_string",
                                        "a",
                                        "new_string",
                                        "b")));
    }

    @Test
    void rejectsV4aTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SandboxPatchTool.validateInput(
                                Map.of(
                                        "mode",
                                        "patch",
                                        "patch",
                                        "*** Begin Patch\n*** Add File: ../secret\n+x\n*** End Patch")));
    }

    @Test
    void appliesReplaceAndMultiFileV4a() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/a.txt"), "heading\nold\n");
        Files.writeString(workspace.resolve("src/move.txt"), "move me\n");
        Files.writeString(workspace.resolve("src/delete.txt"), "delete me\n");
        AbstractFilesystem filesystem = new LocalFilesystem(workspace, false, 10, null);
        RuntimeContext context = context(filesystem);
        SandboxPatchTool tool = new SandboxPatchTool();
        tool.callAsync(
                        ToolCallParam.builder()
                                .runtimeContext(context)
                                .input(
                                        Map.of(
                                                "mode",
                                                "replace",
                                                "path",
                                                "src/a.txt",
                                                "old_string",
                                                "old",
                                                "new_string",
                                                "older"))
                                .build())
                .block();
        assertTrue(Files.readString(workspace.resolve("src/a.txt")).contains("older"));

        String patch =
                """
                        *** Begin Patch
                        *** Update File: src/a.txt
                        @@ heading @@
                         heading
                        -older
                        +new
                        *** Add File: src/added.txt
                        +added
                        *** Move File: src/move.txt -> src/moved.txt
                        *** Delete File: src/delete.txt
                        *** End Patch
                        """;
        var patchResult =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(context)
                                        .input(Map.of("mode", "patch", "patch", patch))
                                        .build())
                        .block();
        String patchOutput = ((TextBlock) patchResult.getOutput().get(0)).getText();
        assertFalse(patchOutput.startsWith("Error"), patchOutput);
        assertEquals("heading\nnew\n", Files.readString(workspace.resolve("src/a.txt")));
        assertEquals("added", Files.readString(workspace.resolve("src/added.txt")));
        assertTrue(Files.exists(workspace.resolve("src/moved.txt")));
        assertFalse(Files.exists(workspace.resolve("src/delete.txt")));
    }

    @Test
    void validationFailureDoesNotWriteAnyFile() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/a.txt"), "original\n");
        AbstractFilesystem filesystem = new LocalFilesystem(workspace, false, 10, null);
        String patch =
                """
                        *** Begin Patch
                        *** Update File: src/a.txt
                        -missing
                        +changed
                        *** Add File: src/should-not-exist.txt
                        +bad
                        *** End Patch
                        """;
        new SandboxPatchTool()
                .callAsync(
                        ToolCallParam.builder()
                                .runtimeContext(context(filesystem))
                                .input(Map.of("mode", "patch", "patch", patch))
                                .build())
                .block();
        assertEquals("original\n", Files.readString(workspace.resolve("src/a.txt")));
        assertFalse(Files.exists(workspace.resolve("src/should-not-exist.txt")));
    }

    private static RuntimeContext context(AbstractFilesystem filesystem) {
        return RuntimeContext.builder()
                .userId("owner")
                .sessionId("session")
                .put(AbstractFilesystem.class, filesystem)
                .build();
    }
}
