package dev.horizen.agent.sandbox.e2b.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import okio.Buffer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 协议模拟器仅在隔离的临时测试目录执行可信测试脚本。
 */
class HttpE2bSnapshotRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    Path testRoot;

    @Test
    void freshPublicationReplacesReservedRootsAfterTaskArchiveRestore() throws Exception {
        MemorySnapshots snapshots = new MemorySnapshots();
        RemoteSnapshotSpec spec = new RemoteSnapshotSpec(snapshots);
        Path nodeA = Files.createDirectory(testRoot.resolve("publication-a"));
        Path nodeB = Files.createDirectory(testRoot.resolve("publication-b"));
        var firstClient = new HttpE2bSandboxClient(options(nodeA, "sandbox-a"), JSON);
        Sandbox first = firstClient.create(new WorkspaceSpec(), spec, null);
        first.start();
        Path old = nodeA.resolve("workspace");
        Files.writeString(old.resolve("report.md"), "user task data");
        Files.writeString(old.resolve("AGENTS.md"), "R1");
        Files.createDirectories(old.resolve("knowledge"));
        Files.writeString(old.resolve("knowledge/deleted.md"), "old knowledge");
        Files.createDirectories(old.resolve(".skills-cache"));
        Files.writeString(old.resolve(".skills-cache/old.py"), "old script");
        first.stop();
        Path publication = Files.createDirectory(testRoot.resolve("publication-r2"));
        Files.writeString(publication.resolve("AGENTS.md"), "R2");
        WorkspaceSpec desired = new WorkspaceSpec();
        var projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(publication.toString());
        projection.setIncludeRoots(
                List.of("AGENTS.md", "knowledge", "subagents", "skills", ".skills-cache"));
        desired.getEntries().put("__workspace_projection__", projection);
        var nextOptions = options(nodeB, "sandbox-b");
        nextOptions.setRefreshPublishedWorkspace(true);
        var secondClient = new HttpE2bSandboxClient(nextOptions, JSON);
        var state =
                secondClient.deserializeState(firstClient.serializeState(first.getState()), spec);
        state.setWorkspaceSpec(desired);
        Sandbox second = secondClient.resume(state);
        second.start();
        assertEquals("R2", Files.readString(nodeB.resolve("workspace/AGENTS.md")));
        assertEquals("user task data", Files.readString(nodeB.resolve("workspace/report.md")));
        assertTrue(Files.notExists(nodeB.resolve("workspace/knowledge/deleted.md")));
        assertTrue(Files.notExists(nodeB.resolve("workspace/.skills-cache/old.py")));
        second.shutdown();
    }

    @Test
    void secondClientRebindsRemoteSnapshotAndRestoresFilesWhenOldSandboxIsGone() throws Exception {
        MemorySnapshots snapshots = new MemorySnapshots();
        RemoteSnapshotSpec spec = new RemoteSnapshotSpec(snapshots);
        Path nodeA = Files.createDirectory(testRoot.resolve("node-a"));
        Path nodeB = Files.createDirectory(testRoot.resolve("node-b"));
        HttpE2bSandboxClient a = new HttpE2bSandboxClient(options(nodeA, "sandbox-a"), JSON);
        Sandbox first = a.create(new WorkspaceSpec(), spec, null);
        first.start();
        Path firstWorkspace = nodeA.resolve("workspace");
        Files.writeString(firstWorkspace.resolve("report.md"), "saved in turn 1");
        byte[] binary = new byte[180000];
        new Random(7).nextBytes(binary);
        Files.write(firstWorkspace.resolve("binary.bin"), binary);
        first.stop();
        String persistedState = a.serializeState(first.getState());
        assertFalse(JSON.readTree(persistedState).has("snapshotSpec"));
        String snapshotId = first.getState().getSnapshot().getId();
        assertTrue(snapshots.exists(snapshotId));

        HttpE2bSandboxClient b = new HttpE2bSandboxClient(options(nodeB, "sandbox-b"), JSON);
        Sandbox second = b.resume(b.deserializeState(persistedState, spec));
        second.start();
        Assertions.assertNotEquals(snapshotId, second.getState().getSnapshot().getId());
        assertTrue(snapshots.exists(snapshotId));
        assertEquals("sandbox-b", ((HttpE2bSandboxState) second.getState()).getSandboxId());
        assertEquals("saved in turn 1", Files.readString(nodeB.resolve("workspace/report.md")));
        assertArrayEquals(binary, Files.readAllBytes(nodeB.resolve("workspace/binary.bin")));
        Files.writeString(nodeB.resolve("workspace/report.md"), "saved in turn 2");
        second.stop();
        assertEquals(2, snapshots.uploads);
        second.stop(); // Harness 清理时不能再次上传已经提交的检查点。
        assertEquals(2, snapshots.uploads);
    }

    @Test
    void failedRestoreCannotOverwriteAnExistingSnapshotDuringCleanup() throws Exception {
        MemorySnapshots snapshots = new MemorySnapshots();
        RemoteSnapshotSpec spec = new RemoteSnapshotSpec(snapshots);
        Path node = Files.createDirectory(testRoot.resolve("broken"));
        var client = new HttpE2bSandboxClient(options(node, "sandbox-broken"), JSON);
        Sandbox sandbox = client.create(new WorkspaceSpec(), spec, null);
        snapshots.content.put(sandbox.getState().getSnapshot().getId(), "invalid tar".getBytes());
        assertThrows(Exception.class, sandbox::start);
        sandbox.stop();
        assertEquals(0, snapshots.uploads);
    }

    @Test
    void oversizedWorkspaceFailsBeforeUploading() throws Exception {
        MemorySnapshots snapshots = new MemorySnapshots();
        Path node = Files.createDirectory(testRoot.resolve("oversize"));
        var options = options(node, "sandbox-large");
        options.setMaxSnapshotBytes(20000);
        Sandbox sandbox =
                new HttpE2bSandboxClient(options, JSON)
                        .create(new WorkspaceSpec(), new RemoteSnapshotSpec(snapshots), null);
        sandbox.start();
        Files.write(node.resolve("workspace/large.bin"), new byte[30000]);
        assertThrows(Exception.class, sandbox::stop);
        assertEquals(0, snapshots.uploads);
    }

    private static HttpE2bSandboxClientOptions options(Path root, String id) {
        var options = new HttpE2bSandboxClientOptions();
        options.setApiKey("fixture-key");
        options.setApiBaseUrl("https://api.example.test");
        options.setRuntimeBaseUrlPattern("https://{sandbox_id}.example.test");
        options.setTemplateId("fixture");
        options.setWorkspaceRoot("/workspace");
        options.setHttpClient(
                new OkHttpClient.Builder().addInterceptor(new RuntimeStub(root, id)).build());
        return options;
    }

    private static final class MemorySnapshots implements RemoteSnapshotClient {
        final Map<String, byte[]> content = new HashMap<>();
        int uploads;

        @Override
        public void upload(String id, InputStream input) throws IOException {
            content.put(id, input.readAllBytes());
            uploads++;
        }

        @Override
        public InputStream download(String id) {
            return new ByteArrayInputStream(content.get(id));
        }

        @Override
        public boolean exists(String id) {
            return content.containsKey(id);
        }
    }

    private static final class RuntimeStub implements Interceptor {
        private final Path root;
        private final String id;

        RuntimeStub(Path root, String id) {
            this.root = root;
            this.id = id;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var request = chain.request();
            String path = request.url().encodedPath();
            if (path.equals("/sandboxes")) {
                return reply(
                        request,
                        201,
                        "{\"sandboxID\":\"" + id + "\",\"envdAccessToken\":\"fixture\"}");
            }
            if (!path.equals("/process.Process/StartSync")) return reply(request, 404, "{}");
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            var process = JSON.readTree(buffer.readUtf8()).path("process");
            String command =
                    process.path("args")
                            .get(2)
                            .asText()
                            .replace("/workspace", root.resolve("workspace").toString())
                            .replace("/tmp/horizen-", root.resolve("horizen-").toString());
            // macOS 的 bsdtar 将 POSIX 格式称为 pax；生产 E2B 使用 GNU tar。
            if (System.getProperty("os.name").contains("Mac"))
                command = command.replace("--format=posix", "--format=pax");
            String cwd = process.path("cwd").asText("/");
            Path directory = cwd.equals("/workspace") ? root.resolve("workspace") : root;
            Path stdout = Files.createTempFile(root, "stdout-", ".txt");
            Path stderr = Files.createTempFile(root, "stderr-", ".txt");
            try {
                Process local =
                        new ProcessBuilder("bash", "-c", command)
                                .directory(directory.toFile())
                                .redirectOutput(stdout.toFile())
                                .redirectError(stderr.toFile())
                                .start();
                if (!local.waitFor(10, TimeUnit.SECONDS)) {
                    local.destroyForcibly();
                    throw new IOException("fixture runtime command timeout");
                }
                var result =
                        JSON.createObjectNode()
                                .put("exitCode", local.exitValue())
                                .put("stdout", Files.readString(stdout))
                                .put("stderr", Files.readString(stderr));
                return reply(request, 200, result.toString());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            } finally {
                Files.deleteIfExists(stdout);
                Files.deleteIfExists(stderr);
            }
        }

        private static Response reply(Request request, int code, String body) {
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fixture")
                    .body(ResponseBody.create(body, MediaType.get("application/json")))
                    .build();
        }
    }
}
