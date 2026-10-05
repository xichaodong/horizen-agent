package dev.horizen.agent.sandbox.e2b.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

class HttpE2bProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void createsSandboxWithManagementJsonContract() throws Exception {
        RecordingStub stub = new RecordingStub();
        HttpE2bSandboxClientOptions options = options(stub);

        JsonNode created = new E2bPlatformClient(options, JSON).create();

        assertEquals("sandbox-1", created.path("sandboxID").asText());
        Captured request = stub.requests.get(0);
        assertEquals("/sandboxes", request.path());
        assertEquals("test-key", request.apiKey());
        assertTrue(request.contentType().startsWith("application/json"));
        JsonNode body = JSON.readTree(request.body());
        assertEquals("test", body.path("templateID").asText());
        assertEquals(300, body.path("timeout").asInt());
    }

    @Test
    void executesPlainJsonAgainstSynchronousEnvdEndpoint() throws Exception {
        RecordingStub stub = new RecordingStub();
        HttpE2bSandboxClientOptions options = options(stub);
        HttpE2bSandboxState state = state();

        var result =
                new EnvdSyncProcessClient(options, JSON)
                        .runShell(state, "/workspace", "printf hello", 30);

        assertEquals(0, result.exitCode());
        assertEquals("hello", result.stdout());
        Captured request = stub.requests.get(0);
        assertEquals("/process.Process/StartSync", request.path());
        assertEquals("test-access-token", request.accessToken());
        assertTrue(request.contentType().startsWith("application/json"));
        assertFalse(request.contentType().startsWith("application/connect+json"));
        JsonNode process = JSON.readTree(request.body()).path("process");
        assertEquals("bash", process.path("cmd").asText());
        assertEquals("-l", process.path("args").get(0).asText());
        assertEquals("-c", process.path("args").get(1).asText());
        assertEquals("printf hello", process.path("args").get(2).asText());
        assertEquals("/workspace", process.path("cwd").asText());
    }

    @Test
    void propagatesNonZeroExitCodeAndDecodedStderr() {
        RecordingStub stub = new RecordingStub();
        stub.commandExitCode = 7;
        stub.commandStdout = "";
        stub.commandStderr = "failed";

        SandboxException.ExecException error =
                assertThrows(
                        SandboxException.ExecException.class,
                        () ->
                                new EnvdSyncProcessClient(options(stub), JSON)
                                        .runShell(state(), "/workspace", "false", 30));

        assertEquals(7, error.getExitCode());
        assertEquals("failed", error.getStderr());
    }

    @Test
    void noopSnapshotResumeCreatesFreshSandboxWithoutCallingRestore() throws Exception {
        RecordingStub stub = new RecordingStub();
        HttpE2bSandboxClientOptions options = options(stub);
        HttpE2bSandboxClient client = new HttpE2bSandboxClient(options, JSON);
        HttpE2bSandboxState previous = state();
        previous.setSessionId("previous-session");
        previous.setWorkspaceSpec(new WorkspaceSpec());
        previous.setSnapshot(new NoopSnapshotSpec().build("ignored"));

        Sandbox sandbox = client.resume(previous);
        sandbox.start();

        assertEquals("/sandboxes", stub.requests.get(0).path());
        assertTrue(
                stub.requests.stream().noneMatch(request -> request.path().contains("/restore")));
    }

    @Test
    void frameworkFileTransferPreservesBinaryDataBeyondTextOutputLimits() throws Exception {
        RecordingStub stub = new RecordingStub();
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 127);
        stub.commandExitCode = 0;
        stub.commandStdout = Base64.getEncoder().encodeToString(bytes);
        var configuration = options(stub);
        configuration.setMaxOutputBytes(16);
        var active = state();
        active.setWorkspaceSpec(new WorkspaceSpec());
        var sandbox = new HttpE2bSandbox(active, configuration, JSON);
        var transfer = Assertions.assertInstanceOf(SandboxFileTransfer.class, sandbox);
        assertTrue(transfer.supportsFileTransfer(".horizen/synthetic.bin"));
        assertFalse(transfer.supportsFileTransfer("../outside.bin"));
        Assertions.assertArrayEquals(bytes, transfer.downloadFile(".horizen/synthetic.bin"));
        assertThrows(IllegalArgumentException.class, () -> transfer.downloadFile("/outside.bin"));
        String command =
                JSON.readTree(stub.requests.get(0).body())
                        .path("process")
                        .path("args")
                        .get(2)
                        .asText();
        assertTrue(command.contains("/workspace/.horizen/synthetic.bin"));
    }

    private static HttpE2bSandboxClientOptions options(Interceptor interceptor) {
        HttpE2bSandboxClientOptions options = new HttpE2bSandboxClientOptions();
        options.setApiKey("test-key");
        options.setApiBaseUrl("https://api.example.test");
        options.setRuntimeBaseUrlPattern("https://49983-{sandbox_id}.example.test");
        options.setTemplateId("test");
        options.setHttpClient(new OkHttpClient.Builder().addInterceptor(interceptor).build());
        return options;
    }

    private static HttpE2bSandboxState state() {
        HttpE2bSandboxState state = new HttpE2bSandboxState();
        state.setSandboxId("sandbox-1");
        state.setAccessToken("test-access-token");
        state.setWorkspaceRoot("/workspace");
        return state;
    }

    private static final class RecordingStub implements Interceptor {
        private final List<Captured> requests = new ArrayList<>();
        private Integer commandExitCode;
        private String commandStdout = "hello";
        private String commandStderr = "";

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            String body = request.body() == null ? "" : body(request);
            requests.add(
                    new Captured(
                            request.url().encodedPath(),
                            request.header("X-API-KEY"),
                            request.header("X-Access-Token"),
                            request.body() == null || request.body().contentType() == null
                                    ? ""
                                    : request.body().contentType().toString(),
                            body));
            if (request.url().encodedPath().equals("/sandboxes")) {
                return response(
                        request,
                        201,
                        "application/json",
                        "{\"sandboxID\":\"sandbox-1\",\"envdAccessToken\":\"test-access-token\"}");
            }
            if (request.url().encodedPath().equals("/process.Process/StartSync")) {
                var result = JSON.createObjectNode();
                if (commandExitCode != null) {
                    result.put("exitCode", commandExitCode);
                }
                result.put("stdout", commandStdout);
                result.put("stderr", commandStderr);
                return response(request, 200, "application/json", result.toString());
            }
            return response(request, 404, "application/json", "{}");
        }

        private static String body(Request request) throws IOException {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            return buffer.readUtf8();
        }

        private static Response response(
                Request request, int status, String mediaType, String body) {
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("stub")
                    .body(ResponseBody.create(body, MediaType.get(mediaType)))
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Captured {
        private String path;
        private String apiKey;
        private String accessToken;
        private String contentType;
        private String body;

        public String path() {
            return path;
        }

        public String apiKey() {
            return apiKey;
        }

        public String accessToken() {
            return accessToken;
        }

        public String contentType() {
            return contentType;
        }

        public String body() {
            return body;
        }
    }
}
