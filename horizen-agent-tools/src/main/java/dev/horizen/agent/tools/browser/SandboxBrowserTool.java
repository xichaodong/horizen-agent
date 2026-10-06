package dev.horizen.agent.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.common.process.ShellQuoteUtils;
import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactDescriptor;
import dev.horizen.agent.domain.artifact.ArtifactEventCollector;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.artifact.ArtifactPublicationRequest;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 由活跃沙箱内固定 CDP 驱动执行的结构化浏览器操作。
 */
public final class SandboxBrowserTool extends ToolBase {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 校验SENSITIVE查询的模式，限定允许接受的输入形式。
     */
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile("(?i)(token|api[_-]?key|secret|authorization|signature)=");

    /**
     * 校验令牌值的模式，限定允许接受的输入形式。
     */
    private static final Pattern TOKEN_VALUE =
            Pattern.compile(
                    "(?i)(bearer\\s+|sk-[a-z0-9_-]{8,}|access[_-]?token[=:]\\s*)[^\\s,\\\"]+");

    /**
     * 当前工具执行的具体动作实现。
     */
    private final String action;

    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    private final ArtifactLifecycleService artifacts;

    /**
     * 创建沙箱浏览器工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param action      在当前处理边界中执行的操作。
     * @param description 当前沙箱浏览器工具的用途说明，供目录或配置阅读者理解。
     * @param schema      Schema的索引映射，供按键查找或归并当前组件的数据。
     * @param artifacts   产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    SandboxBrowserTool(
            String action,
            String description,
            Map<String, Object> schema,
            ArtifactLifecycleService artifacts) {
        super(
                ToolBase.builder()
                        .name("browser_" + action)
                        .description(description)
                        .inputSchema(schema)
                        .readOnly("snapshot".equals(action))
                        .concurrencySafe(false));
        this.action = action;
        this.artifacts = artifacts;
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前沙箱浏览器工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> invoke(param));
    }

    /**
     * 调用沙箱浏览器工具。
     *
     * @param param 当前沙箱浏览器工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock invoke(ToolCallParam param) throws Exception {
        AbstractFilesystem fs = param.getRuntimeContext().get(AbstractFilesystem.class);
        if (!(fs instanceof AbstractSandboxFilesystem sandbox))
            return ToolResultBlock.error("browser requires an active sandbox");
        ObjectNode request = JSON.createObjectNode();
        request.put("action", action);
        param.getInput().forEach((k, v) -> request.set(k, JSON.valueToTree(v)));
        if ("navigate".equals(action)) {
            String blocked = validateNavigation(request.path("url").asText());
            if (blocked != null) return ToolResultBlock.error(blocked);
        }
        if ("screenshot".equals(action))
            request.put("file_name", "browser-screenshot-" + UUID.randomUUID() + ".png");
        if ("download".equals(action))
            request.put("file_name", "browser-download-" + UUID.randomUUID() + ".bin");
        String session = browserSession(param);
        String client = "agent-browser --session " + session + " --json ";
        String marker = ".horizen/browser-" + session + ".connected";
        String prepare =
                "mkdir -p .horizen; test -f "
                        + quote(marker)
                        + " || { "
                        + client
                        + "connect 9222 >/dev/null && : >"
                        + quote(marker)
                        + "; }; ";
        String prefix = client;
        String command;
        if ("navigate".equals(action))
            command =
                    prefix
                            + "open "
                            + quote(request.path("url").asText())
                            + " && "
                            + prefix
                            + "snapshot -c";
        else if ("snapshot".equals(action)) command = prefix + "snapshot -c";
        else if ("click".equals(action)) command = prefix + "click " + quote(ref(request));
        else if ("type".equals(action))
            command =
                    prefix
                            + "fill "
                            + quote(ref(request))
                            + " "
                            + quote(request.path("text").asText());
        else if ("scroll".equals(action))
            command = prefix + "scroll " + quote(request.path("direction").asText());
        else if ("back".equals(action)) command = prefix + "back";
        else if ("press".equals(action))
            command = prefix + "press " + quote(request.path("key").asText());
        else if ("get_images".equals(action))
            command =
                    prefix
                            + "eval "
                            + quote(
                            "JSON.stringify([...document.images].map(img=>({src:img.src,alt:img.alt||'',width:img.naturalWidth,height:img.naturalHeight})).filter(img=>img.src&&!img.src.startsWith('data:')))"
                                    + " ");
        else if ("console".equals(action))
            command =
                    prefix + "console" + (request.path("clear").asBoolean(false) ? " --clear" : "");
        else
            command =
                    prefix
                            + ("screenshot".equals(action)
                            ? "screenshot "
                            : "download " + quote(ref(request)) + " ")
                            + "\"$PWD/\""
                            + quote(".horizen/" + request.path("file_name").asText());
        command = prepare + command;
        ExecuteResponse response = sandbox.execute(param.getRuntimeContext(), command, 60);
        if (!response.isSuccess())
            return ToolResultBlock.error("browser driver failed: " + response.output());
        String output = response.output().trim();
        JsonNode result = null;
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            JsonNode current = JSON.readTree(line);
            if (current.path("success").isBoolean() && !current.path("success").asBoolean()
                    || current.hasNonNull("error") && !current.path("error").asText().isBlank()) {
                return ToolResultBlock.error(
                        current.path("error").asText("browser command failed"));
            }
            result = current;
        }
        if (result == null) return ToolResultBlock.error("browser driver returned no JSON result");
        if (!"screenshot".equals(action) && !"download".equals(action))
            return ToolResultBlock.text(redact(result.toString()));
        String image = request.path("file_name").asText();
        if (image.isBlank()) return ToolResultBlock.error("browser output file was empty");
        var files = fs.downloadFiles(param.getRuntimeContext(), List.of(".horizen/" + image));
        if (files.size() != 1 || !files.get(0).isSuccess() || files.get(0).content() == null)
            return ToolResultBlock.error("browser output file download failed");
        byte[] bytes = files.get(0).content();
        if (bytes.length > 32 * 1024 * 1024)
            return ToolResultBlock.error("browser output exceeds 32 MiB");
        Artifact artifact =
                publish(
                        param,
                        bytes,
                        "screenshot".equals(action)
                                ? "browser-screenshot.png"
                                : "browser-download.bin",
                        "screenshot".equals(action) ? "image/png" : "application/octet-stream");
        ObjectNode out =
                JSON.createObjectNode()
                        .put("status", "success")
                        .put("artifact_id", artifact.getArtifactId())
                        .put(
                                "media_type",
                                "screenshot".equals(action)
                                        ? "image/png"
                                        : "application/octet-stream");
        return ToolResultBlock.text(out.toString());
    }

    /**
     * 校验Navigation。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String validateNavigation(String value) {
        try {
            URI uri = URI.create(value);
            if ("data".equalsIgnoreCase(uri.getScheme())
                    || "about".equalsIgnoreCase(uri.getScheme())) return null;
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())))
                return "browser only supports HTTP(S) URLs";
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (host.equals("169.254.169.254")
                    || host.equals("metadata.google.internal")
                    || host.contains("metadata.azure"))
                return "Blocked: URL targets a cloud metadata endpoint";
            if (SENSITIVE_QUERY.matcher(uri.getRawQuery() == null ? "" : uri.getRawQuery()).find())
                return "Blocked: URL contains credential-like query parameters";
            return null;
        } catch (IllegalArgumentException error) {
            return "invalid browser URL";
        }
    }

    /**
     * 脱敏沙箱浏览器工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String redact(String value) {
        return TOKEN_VALUE.matcher(value).replaceAll("$1[REDACTED]");
    }

    /**
     * 生成当前操作所需的ref文本，供调用方继续处理。
     *
     * @param request 当前操作的请求参数。
     * @return 本次处理生成或读取的文本。
     */
    private static String ref(ObjectNode request) {
        return request.path("ref").asText().replaceFirst("^@", "");
    }

    /**
     * 生成当前操作所需的browserSession文本，供调用方继续处理。
     *
     * @param param 当前沙箱浏览器工具持有的参数对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String browserSession(ToolCallParam param) {
        String owner =
                param.getRuntimeContext().getUserId() == null
                        ? ""
                        : param.getRuntimeContext().getUserId();
        String session =
                param.getRuntimeContext().getSessionId() == null
                        ? ""
                        : param.getRuntimeContext().getSessionId();
        try {
            byte[] digest =
                    DigestUtils.newSha256()
                            .digest((owner + '\0' + session).getBytes(StandardCharsets.UTF_8));
            return "hz-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /**
     * 转义沙箱浏览器工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String quote(String value) {
        return ShellQuoteUtils.quote(value);
    }

    /**
     * 发布沙箱浏览器工具。
     *
     * @param param     当前沙箱浏览器工具持有的参数对象，供相应处理步骤使用。
     * @param bytes     当前操作处理的内容字节。
     * @param title     当前沙箱浏览器工具的可读标题，供宿主界面展示。
     * @param mediaType 当前沙箱浏览器工具使用的媒体类型，供其处理与状态记录使用。
     * @return 本次操作返回的产物结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private Artifact publish(ToolCallParam param, byte[] bytes, String title, String mediaType) {
        if (artifacts == null)
            throw new IllegalStateException("artifact storage is not configured");
        ArtifactExecutionContext execution =
                param.getRuntimeContext().get(ArtifactExecutionContext.class);
        if (execution == null)
            throw new IllegalStateException("missing artifact execution context");
        Artifact artifact =
                artifacts.publishFile(
                        new ArtifactPublicationRequest(
                                param.getRuntimeContext().getUserId(),
                                param.getRuntimeContext().getSessionId(),
                                execution.getTurnId(),
                                "browser:" + action + ":" + UUID.randomUUID(),
                                title,
                                mediaType,
                                bytes,
                                null,
                                null,
                                Instant.now()));
        ArtifactEventCollector collector =
                param.getRuntimeContext().get(ArtifactEventCollector.class);
        if (collector != null) collector.record(new ArtifactDescriptor(artifact));
        return artifact;
    }

    /**
     * 创建全部。
     *
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @return 本次处理得到的结果集合。
     */
    public static List<SandboxBrowserTool> createAll(ArtifactLifecycleService artifacts) {
        return List.of(
                new SandboxBrowserTool(
                        "navigate",
                        "打开 URL，并返回含 ref 的交互元素快照。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("url", Map.of("type", "string")),
                                "required",
                                List.of("url"),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "snapshot",
                        "读取当前页面的交互快照；返回的 ref 用于点击和输入。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "click",
                        "点击 snapshot 返回的 ref，例如 e1 或 @e1。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("ref", Map.of("type", "string")),
                                "required",
                                List.of("ref"),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "type",
                        "向 snapshot 返回的输入框 ref 填入文本。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "ref",
                                        Map.of("type", "string"),
                                        "text",
                                        Map.of("type", "string")),
                                "required",
                                List.of("ref", "text"),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "scroll",
                        "滚动当前页面。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "direction",
                                        Map.of("type", "string", "enum", List.of("up", "down"))),
                                "required",
                                List.of("direction"),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "back",
                        "返回浏览器历史上一页。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "press",
                        "向当前页面发送键盘按键。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("key", Map.of("type", "string")),
                                "required",
                                List.of("key"),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "get_images",
                        "列出当前页面图片。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "console",
                        "读取当前页面控制台消息。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("clear", Map.of("type", "boolean")),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "dialog",
                        "响应当前页面原生对话框。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "response",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of("accept", "dismiss")),
                                        "prompt_text",
                                        Map.of("type", "string")),
                                "required",
                                List.of("response"),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "download",
                        "点击下载元素 ref 并发布文件 Artifact。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("ref", Map.of("type", "string")),
                                "required",
                                List.of("ref"),
                                "additionalProperties",
                                false),
                        artifacts),
                new SandboxBrowserTool(
                        "screenshot",
                        "截取当前页面并发布 PNG Artifact。",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(),
                                "additionalProperties",
                                false),
                        artifacts));
    }
}
