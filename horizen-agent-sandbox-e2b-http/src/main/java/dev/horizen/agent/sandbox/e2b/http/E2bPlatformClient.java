package dev.horizen.agent.sandbox.e2b.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.json.JsonUtils;

import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** E2B 管理面客户端，支持创建、恢复、详情和销毁。 */
final class E2bPlatformClient {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 当前远端协议的 JSON 编解码器。 */
    private final ObjectMapper json;

    /** 用于实际网络请求的共享 HTTP 客户端。 */
    private final OkHttpClient http;

    /** 可供当前请求选择的选项或策略集合。 */
    private final HttpE2bSandboxClientOptions options;

    /**
     * 创建2B平台客户端，初始化该组件所需的状态、配置或依赖。
     *
     * @param options 可供当前请求选择的选项或策略集合。
     * @param json 提供JSON能力的依赖，具体实现由当前组件的组装方传入。
     */
    E2bPlatformClient(HttpE2bSandboxClientOptions options, ObjectMapper json) {
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
     * 调用沙箱控制面创建执行环境，传入模板、时限与隔离配置。
     *
     * @return 本次操作返回的JSON节点结果。
     */
    JsonNode create() throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("templateID", required(options.getTemplateId(), "templateId"));
        body.put("timeout", options.getSandboxTimeoutSeconds());
        putMap(body, "envVars", options.getEnvironment());
        putMap(body, "metadata", options.getMetadata());
        return post(base() + "/sandboxes", body);
    }

    /**
     * 通过沙箱控制面恢复指定的执行环境描述。
     *
     * @param sandboxId 沙箱的标识，用于关联相应记录或执行。
     * @return 本次操作返回的JSON节点结果。
     */
    JsonNode restore(String sandboxId) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("timeout", options.getSandboxTimeoutSeconds());
        putMap(body, "envVars", options.getEnvironment());
        JsonNode restored = post(base() + "/sandboxes/" + sandboxId + "/restore", body);
        if (hasIdentity(restored)) {
            return restored;
        }
        return detail(sandboxId);
    }

    /**
     * 计算或取得本方法声明的结果，供当前E2bPlatformClient处理步骤使用。
     *
     * @param sandboxId 沙箱的标识，用于关联相应记录或执行。
     * @return 本次操作返回的JSON节点结果。
     */
    JsonNode detail(String sandboxId) throws IOException {
        Request request =
                authenticated(new Request.Builder().url(base() + "/sandboxes/" + sandboxId).get())
                        .build();
        return call(request);
    }

    /**
     * 请求控制面销毁指定沙箱资源。
     *
     * @param sandboxId 沙箱的标识，用于关联相应记录或执行。
     */
    void delete(String sandboxId) throws IOException {
        Request request =
                authenticated(
                                new Request.Builder()
                                        .url(base() + "/sandboxes/" + sandboxId)
                                        .delete())
                        .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 404 && response.code() != 410) {
                throw failure("删除沙箱失败", response);
            }
        }
    }

    /**
     * 把宿主生成的隔离身份放入控制面请求的对应字段。
     *
     * @param state 当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @param node 当前2B平台客户端持有的节点对象，供相应处理步骤使用。
     */
    void applyIdentity(HttpE2bSandboxState state, JsonNode node) {
        String id = text(node, "sandboxID");
        if (id != null) {
            state.setSandboxId(id);
        }
        String token = text(node, "envdAccessToken");
        if (token != null) {
            state.setAccessToken(token);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前E2bPlatformClient处理步骤使用。
     *
     * @param url 资源或远端接口地址；具体访问范围由所属服务的配置校验。
     * @param body 当前2B平台客户端持有的正文对象，供相应处理步骤使用。
     * @return 本次操作返回的JSON节点结果。
     */
    private JsonNode post(String url, ObjectNode body) throws IOException {
        Request request =
                authenticated(
                                new Request.Builder()
                                        .url(url)
                                        .post(RequestBody.create(body.toString(), JSON)))
                        .build();
        return call(request);
    }

    /**
     * 在规定超时内调用沙箱控制面，并将失败转换成适配器异常。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的JSON节点结果。
     */
    private JsonNode call(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw failure("沙箱管理请求失败", response.code(), text);
            }
            return text.isBlank() ? json.createObjectNode() : json.readTree(text);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前E2bPlatformClient处理步骤使用。
     *
     * @param builder 当前2B平台客户端持有的构造器对象，供相应处理步骤使用。
     * @return 本次操作返回的构造器结果。
     */
    private Request.Builder authenticated(Request.Builder builder) {
        return builder.header("X-API-KEY", required(options.getApiKey(), "apiKey"))
                .header("Accept", "application/json");
    }

    /**
     * 生成当前操作所需的base文本，供调用方继续处理。
     *
     * @return 本次处理生成或读取的文本。
     */
    private String base() {
        String value = required(options.getApiBaseUrl(), "apiBaseUrl");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 判断是否存在身份。
     *
     * @param node 当前2B平台客户端持有的节点对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean hasIdentity(JsonNode node) {
        return node != null && node.hasNonNull("sandboxID") && node.hasNonNull("envdAccessToken");
    }

    /**
     * 写入映射。
     *
     * @param target 本次转换、状态更新或内容写入的目标。
     * @param name 需要定位或处理的名称。
     * @param values 本次批量处理的值集合。
     */
    private static void putMap(ObjectNode target, String name, Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            target.set(name, JsonUtils.newMapper().valueToTree(values));
        }
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param node 当前2B平台客户端持有的节点对象，供相应处理步骤使用。
     * @param field 当前2B平台客户端使用的字段，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }

    /**
     * 生成当前操作所需的required文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name 需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     */
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(name + " 未配置");
        }
        return value.trim();
    }

    /**
     * 计算或取得本方法声明的结果，供当前E2bPlatformClient处理步骤使用。
     *
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @param response 当前操作得到的响应。
     * @return 本次操作返回的沙箱运行时异常结果。
     */
    private static SandboxException.SandboxRuntimeException failure(
            String message, Response response) throws IOException {
        String body = response.body() == null ? "" : response.body().string();
        return failure(message, response.code(), body);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param body 当前2B平台客户端使用的正文，供其处理与状态记录使用。
     * @return 本次操作返回的沙箱运行时异常结果。
     */
    private static SandboxException.SandboxRuntimeException failure(
            String message, int status, String body) {
        return new SandboxException.SandboxRuntimeException(
                SandboxErrorCode.WORKSPACE_START_ERROR,
                message + "：HTTP " + status + (body.isBlank() ? "" : "，" + body));
    }
}
