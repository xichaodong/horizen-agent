package dev.horizen.agent.sandbox.e2b.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 使用普通 JSON 请求调用 envd 同步命令接口。
 */
final class EnvdSyncProcessClient {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * 当前远端协议的 JSON 编解码器。
     */
    private final ObjectMapper json;

    /**
     * 用于实际网络请求的共享 HTTP 客户端。
     */
    private final OkHttpClient http;

    /**
     * 可供当前请求选择的选项或策略集合。
     */
    private final HttpE2bSandboxClientOptions options;

    /**
     * 创建envd同步进程客户端，初始化该组件所需的状态、配置或依赖。
     *
     * @param options 可供当前请求选择的选项或策略集合。
     * @param json    提供JSON能力的依赖，具体实现由当前组件的组装方传入。
     */
    EnvdSyncProcessClient(HttpE2bSandboxClientOptions options, ObjectMapper json) {
        this.options = options;
        this.json = json;
        this.http =
                options.getHttpClient() != null
                        ? options.getHttpClient()
                        : new OkHttpClient.Builder()
                        .connectTimeout(
                                options.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                        .readTimeout(options.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                        .build();
    }

    /**
     * 通过 envd 同步接口运行 shell 命令，限制可保留的文本输出容量。
     *
     * @param state          当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @param cwd            当前envd同步进程客户端使用的cwd，供其处理与状态记录使用。
     * @param command        当前envd同步进程客户端使用的命令，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     * @return 本次操作返回的Exec结果结果。
     */
    ExecResult runShell(HttpE2bSandboxState state, String cwd, String command, int timeoutSeconds)
            throws Exception {
        Capture capture = run(state, cwd, command, timeoutSeconds);
        boolean truncated =
                capture.getStdout().getBytes(StandardCharsets.UTF_8).length
                        > options.getMaxOutputBytes()
                        || capture.getStderr().getBytes(StandardCharsets.UTF_8).length
                        > options.getMaxOutputBytes();
        ExecResult result =
                new ExecResult(
                        capture.getExitCode(),
                        truncate(capture.getStdout()),
                        truncate(capture.getStderr()),
                        truncated);
        if (!result.ok()) {
            String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
            if (detail.isBlank()) {
                detail = "envd response shape: " + capture.getResponseShape();
            }
            throw new SandboxException.ExecException(result.exitCode(), result.stdout(), detail);
        }
        return result;
    }

    /**
     * 运行命令并保留二进制标准输出，使用单独的传输容量限制。
     *
     * @param state          当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @param cwd            当前envd同步进程客户端使用的cwd，供其处理与状态记录使用。
     * @param command        当前envd同步进程客户端使用的命令，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     * @return 本次处理取得或生成的内容字节。
     */
    byte[] runShellBinaryStdout(
            HttpE2bSandboxState state, String cwd, String command, int timeoutSeconds)
            throws Exception {
        return runShellBinaryStdout(state, cwd, command, timeoutSeconds, 32 * 1024 * 1024);
    }

    /**
     * 运行命令并保留二进制标准输出，使用单独的传输容量限制。
     *
     * @param state          当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @param cwd            当前envd同步进程客户端使用的cwd，供其处理与状态记录使用。
     * @param command        当前envd同步进程客户端使用的命令，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     * @param maxBytes       本次处理或传输允许的最大字节数。
     * @return 本次处理取得或生成的内容字节。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    byte[] runShellBinaryStdout(
            HttpE2bSandboxState state, String cwd, String command, int timeoutSeconds, int maxBytes)
            throws Exception {
        String encodedCommand = "set -o pipefail; (" + command + ") | base64 | tr -d '\\n'";
        Capture capture = run(state, cwd, encodedCommand, timeoutSeconds, maxBytes * 2 + 65536);
        if (capture.getExitCode() != 0) {
            throw new SandboxException.ExecException(
                    capture.getExitCode(), "(binary stdout)", capture.getStderr());
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(capture.getStdout().trim());
            if (bytes.length > maxBytes) throw new IOException("binary output exceeds byte limit");
            return bytes;
        } catch (IllegalArgumentException error) {
            throw new IOException("envd 同步接口返回的二进制输出不是合法 Base64", error);
        }
    }

    /**
     * 构造并发送 envd 同步命令请求，解析进程退出与输出结果。
     *
     * @param state          当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @param cwd            当前envd同步进程客户端使用的cwd，供其处理与状态记录使用。
     * @param command        当前envd同步进程客户端使用的命令，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     * @return 本次操作返回的采集结果。
     */
    private Capture run(HttpE2bSandboxState state, String cwd, String command, int timeoutSeconds)
            throws Exception {
        return run(state, cwd, command, timeoutSeconds, 0);
    }

    /**
     * 构造并发送 envd 同步命令请求，解析进程退出与输出结果。
     *
     * @param state            当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @param cwd              当前envd同步进程客户端使用的cwd，供其处理与状态记录使用。
     * @param command          当前envd同步进程客户端使用的命令，供其处理与状态记录使用。
     * @param timeoutSeconds   超时，单位为秒。
     * @param maxResponseBytes 最大响应的字节数，用于容量或传输限制。
     * @return 本次操作返回的采集结果。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private Capture run(
            HttpE2bSandboxState state,
            String cwd,
            String command,
            int timeoutSeconds,
            int maxResponseBytes)
            throws Exception {
        ObjectNode root = json.createObjectNode();
        ObjectNode process = root.putObject("process");
        process.put("cmd", "bash");
        process.putArray("args").add("-l").add("-c").add(command);
        if (cwd != null && !cwd.isBlank()) {
            process.put("cwd", cwd);
        }
        OkHttpClient callClient =
                timeoutSeconds > 0
                        ? http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
                        : http;
        Request request =
                new Request.Builder()
                        .url(runtimeUrl(state) + "/process.Process/StartSync")
                        .header(
                                "X-Access-Token",
                                required(state.getAccessToken(), "envdAccessToken"))
                        .header("Accept", "application/json")
                        .post(RequestBody.create(root.toString(), JSON))
                        .build();

        try (Response response = callClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "envd 命令请求失败：HTTP " + response.code());
            }
            JsonNode result;
            if (maxResponseBytes > 0) {
                byte[] bytes = response.body().byteStream().readNBytes(maxResponseBytes + 1);
                if (bytes.length > maxResponseBytes)
                    throw new IOException("envd binary response exceeds byte limit");
                result = json.readTree(bytes);
            } else {
                result = json.readTree(response.body().string());
            }
            JsonNode value =
                    result.has("exitCode") ? result.get("exitCode") : result.get("exit_code");
            return new Capture(
                    parseExitCode(value),
                    text(result.get("stdout")),
                    text(result.get("stderr")),
                    shape(result));
        } catch (InterruptedIOException error) {
            throw new SandboxException.ExecTimeoutException(command, timeoutSeconds);
        }
    }

    /**
     * 根据沙箱身份与运行端地址模板构造 envd 访问地址。
     *
     * @param state 当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @return 本次处理生成或读取的文本。
     */
    private String runtimeUrl(HttpE2bSandboxState state) {
        String pattern = required(options.getRuntimeBaseUrlPattern(), "runtimeBaseUrlPattern");
        String value = pattern.replace("{sandbox_id}", required(state.getSandboxId(), "sandboxId"));
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 从命令响应中读取进程退出码，保持失败结果可被宿主观察。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的整数结果。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static int parseExitCode(JsonNode value) throws IOException {
        if (value == null || value.isNull()) {
            return 0;
        }
        if (value.canConvertToInt()) {
            return value.intValue();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.textValue());
            } catch (NumberFormatException error) {
                throw new IOException("envd 返回了非法退出码", error);
            }
        }
        throw new IOException("envd 返回了不支持的退出码类型：" + value.getNodeType());
    }

    /**
     * 生成当前操作所需的truncate文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private String truncate(String value) {
        return value.length() <= options.getMaxOutputBytes()
                ? value
                : value.substring(0, options.getMaxOutputBytes());
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText("");
    }

    /**
     * 生成当前操作所需的shape文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String shape(JsonNode value) {
        if (value == null || !value.isObject()) {
            return value == null ? "null" : value.getNodeType().name();
        }
        List<String> fields = new ArrayList<>();
        value.fields()
                .forEachRemaining(
                        entry ->
                                fields.add(
                                        entry.getKey()
                                                + ":"
                                                + entry.getValue().getNodeType().name()));
        return fields.toString();
    }

    /**
     * 生成当前操作所需的required文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name  需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     */
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(name + " 未配置");
        }
        return value.trim();
    }

    /**
     * 同步命令输出的捕获结果，用于限制文本输出容量。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Capture {
        /**
         * 远端命令进程的退出码；非零值按命令结果规则解释为失败。
         */
        private int exitCode;

        /**
         * 命令或进程的标准输出内容。
         */
        private String stdout;

        /**
         * 命令或进程的标准错误输出内容。
         */
        private String stderr;

        /**
         * 用于诊断的响应结构摘要，不作为真实命令输出。
         */
        private String responseShape;
    }
}
