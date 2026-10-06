package dev.horizen.agent.tools.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.common.http.BoundedBodyHandlers;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactDescriptor;
import dev.horizen.agent.domain.artifact.ArtifactEventCollector;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.artifact.ArtifactPublicationRequest;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从公开 HTML/PDF URL 提取可读文本，不使用模型生成摘要。
 */
public final class WebExtractTool extends ToolBase {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 默认上限的固定取值，用于相应策略和边界判断。
     */
    private static final int DEFAULT_LIMIT = 15_000;

    /**
     * 最小上限的固定取值，用于相应策略和边界判断。
     */
    private static final int MIN_LIMIT = 2_000;

    /**
     * 最大上限的固定取值，用于相应策略和边界判断。
     */
    private static final int MAX_LIMIT = 500_000;

    /**
     * 最大响应字节的固定取值，用于相应策略和边界判断。
     */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /**
     * 校验SENSITIVE查询的模式，限定允许接受的输入形式。
     */
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile(
                    "(?i)(?:^|[?&])(token|api[_-]?key|access[_-]?token|authorization|signature|secret)=");

    /**
     * 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     */
    private final HttpClient client;

    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    private final ArtifactLifecycleService artifacts;

    /**
     * 创建Web提取工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    public WebExtractTool(ArtifactLifecycleService artifacts) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                artifacts);
    }

    /**
     * 创建Web提取工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param client    当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    public WebExtractTool(HttpClient client, ArtifactLifecycleService artifacts) {
        super(
                ToolBase.builder()
                        .name("web_extract")
                        .description(
                                "提取公开 HTTP(S) 网页或 PDF 的正文。支持最多 5 个 URL；不做模型摘要。"
                                        + "超长正文会返回头尾，并保存完整文本为 Artifact。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "urls",
                                                Map.of(
                                                        "type",
                                                        "array",
                                                        "items",
                                                        Map.of("type", "string"),
                                                        "minItems",
                                                        1,
                                                        "maxItems",
                                                        5,
                                                        "description",
                                                        "待提取的公开 URL"),
                                                "char_limit",
                                                Map.of(
                                                        "type",
                                                        "integer",
                                                        "minimum",
                                                        MIN_LIMIT,
                                                        "maximum",
                                                        MAX_LIMIT,
                                                        "description",
                                                        "每页返回字符上限，默认 15000")),
                                        "required",
                                        List.of("urls"),
                                        "additionalProperties",
                                        false))
                        .readOnly(true)
                        .concurrencySafe(true));
        this.client = client;
        this.artifacts = artifacts;
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前Web提取工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> extract(param));
    }

    /**
     * 提取Web提取工具。
     *
     * @param param 当前Web提取工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock extract(ToolCallParam param) {
        Object urlsValue = param.getInput().get("urls");
        if (!(urlsValue instanceof List<?> values) || values.isEmpty() || values.size() > 5) {
            return ToolResultBlock.error("web_extract requires urls with 1 to 5 entries");
        }
        int limit = limit(param.getInput().get("char_limit"));
        ArrayNode results = JSON.createArrayNode();
        for (Object value : values) {
            ObjectNode result = results.addObject();
            if (!(value instanceof String url)) {
                result.put("url", "").put("error", "URL must be a string");
                continue;
            }
            extractOne(url, limit, param, result);
        }
        ObjectNode payload = JSON.createObjectNode().put("status", "success");
        payload.set("results", results);
        return ToolResultBlock.text(payload.toString());
    }

    /**
     * 提取One。
     *
     * @param value  待校验、转换或保存的原始值。
     * @param limit  本次处理或返回数量上限。
     * @param param  当前Web提取工具持有的参数对象，供相应处理步骤使用。
     * @param result 本次处理已有的结果。
     */
    private void extractOne(String value, int limit, ToolCallParam param, ObjectNode result) {
        String url = value == null ? "" : value.trim();
        result.put("url", url);
        try {
            URI uri = safeUri(url);
            HttpResponse<byte[]> response =
                    client.send(
                            HttpRequest.newBuilder(uri)
                                    .timeout(Duration.ofSeconds(30))
                                    .header("User-Agent", "Horizen-Agent/1.0")
                                    .header(
                                            "Accept",
                                            "text/html,application/pdf,text/plain;q=0.9,*/*;q=0.1")
                                    .GET()
                                    .build(),
                            BoundedBodyHandlers.bytes(MAX_RESPONSE_BYTES));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.put("error", "HTTP " + response.statusCode());
                return;
            }
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            if (bytes.length > MAX_RESPONSE_BYTES) {
                result.put("error", "response exceeds 8 MiB limit");
                return;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            Extracted extracted =
                    isPdf(uri, contentType)
                            ? extractPdf(bytes)
                            : extractHtml(bytes, uri, contentType);
            result.put("title", extracted.getTitle());
            result.put("content_type", contentType);
            result.put("total_chars", extracted.getContent().length());
            if (extracted.getContent().length() <= limit) {
                result.put("content", extracted.getContent()).put("truncated", false);
                return;
            }
            Artifact artifact =
                    publishFullText(url, extracted.getTitle(), extracted.getContent(), param);
            result.put("content", truncate(extracted.getContent(), limit)).put("truncated", true);
            if (artifact != null) result.put("artifact_id", artifact.getArtifactId());
        } catch (IllegalArgumentException error) {
            result.put("error", error.getMessage());
        } catch (Exception error) {
            result.put("error", "extract failed: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 发布Full文本。
     *
     * @param url   资源或远端接口地址；具体访问范围由所属服务的配置校验。
     * @param title 当前Web提取工具的可读标题，供宿主界面展示。
     * @param text  面向消息或事件消费者的文本内容。
     * @param param 当前Web提取工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的产物结果。
     */
    private Artifact publishFullText(String url, String title, String text, ToolCallParam param) {
        if (artifacts == null || param.getRuntimeContext() == null) return null;
        ArtifactExecutionContext execution =
                param.getRuntimeContext().get(ArtifactExecutionContext.class);
        if (execution == null) return null;
        String fileName = safeFileName(title, url);
        Artifact artifact =
                artifacts.publishFile(
                        new ArtifactPublicationRequest(
                                param.getRuntimeContext().getUserId(),
                                param.getRuntimeContext().getSessionId(),
                                execution.getTurnId(),
                                "web_extract:" + sha256(url),
                                fileName,
                                "text/markdown",
                                text.getBytes(StandardCharsets.UTF_8),
                                null,
                                null,
                                Instant.now()));
        ArtifactEventCollector collector =
                param.getRuntimeContext().get(ArtifactEventCollector.class);
        if (collector != null) collector.record(new ArtifactDescriptor(artifact));
        return artifact;
    }

    /**
     * 生成当前操作所需的truncate文本，供调用方继续处理。
     *
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param limit   本次处理或返回数量上限。
     * @return 本次处理生成或读取的文本。
     */
    static String truncate(String content, int limit) {
        int headLimit = (int) (limit * 0.75);
        int tailLimit = limit - headLimit;
        String head = content.substring(0, headLimit);
        String tail = content.substring(content.length() - tailLimit);
        int headBreak = head.lastIndexOf('\n');
        if (headBreak > headLimit / 2) head = head.substring(0, headBreak);
        int tailBreak = tail.indexOf('\n');
        if (tailBreak >= 0 && tailBreak < tailLimit / 2) tail = tail.substring(tailBreak + 1);
        return head + "\n\n[... middle omitted; load artifact_id for the full text ...]\n\n" + tail;
    }

    /**
     * 提取Html。
     *
     * @param bytes       当前操作处理的内容字节。
     * @param uri         当前Web提取工具持有的URI对象，供相应处理步骤使用。
     * @param contentType 当前Web提取工具使用的正文类型，供其处理与状态记录使用。
     * @return 本次操作返回的提取结果结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static Extracted extractHtml(byte[] bytes, URI uri, String contentType) throws Exception {
        Document document = Jsoup.parse(new ByteArrayInputStream(bytes), null, uri.toString());
        document.select("script,style,noscript,nav,header,footer,aside,form,svg").remove();
        String text =
                document.body() == null
                        ? ""
                        : document.body()
                        .text()
                        .replaceAll("[ \\t]+", " ")
                        .replaceAll("\\s*\\n\\s*", "\n")
                        .trim();
        if (text.isEmpty()) throw new IllegalArgumentException("page contains no extractable text");
        return new Extracted(document.title().isBlank() ? uri.getHost() : document.title(), text);
    }

    /**
     * 提取Pdf。
     *
     * @param bytes 当前操作处理的内容字节。
     * @return 本次操作返回的提取结果结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static Extracted extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document).trim();
            if (text.isEmpty())
                throw new IllegalArgumentException("PDF contains no extractable text");
            return new Extracted("PDF document", text);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前WebExtractTool处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的URI结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static URI safeUri(String value) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || SENSITIVE_QUERY
                .matcher(uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                .find()) {
            throw new IllegalArgumentException(
                    "only public HTTP(S) URLs without credentials are allowed");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("private or internal URL is not allowed");
                }
            }
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("URL host cannot be resolved");
        }
        return uri;
    }

    /**
     * 判断Pdf。
     *
     * @param uri         当前Web提取工具持有的URI对象，供相应处理步骤使用。
     * @param contentType 当前Web提取工具使用的正文类型，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean isPdf(URI uri, String contentType) {
        return contentType.toLowerCase(Locale.ROOT).contains("application/pdf")
                || uri.getPath().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /**
     * 计算或取得本方法声明的结果，供当前WebExtractTool处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的整数结果。
     */
    private static int limit(Object value) {
        if (!(value instanceof Number number)) return DEFAULT_LIMIT;
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, number.intValue()));
    }

    /**
     * 生成当前操作所需的safeFileName文本，供调用方继续处理。
     *
     * @param title 当前Web提取工具的可读标题，供宿主界面展示。
     * @param url   资源或远端接口地址；具体访问范围由所属服务的配置校验。
     * @return 本次处理生成或读取的文本。
     */
    private static String safeFileName(String title, String url) {
        String base =
                (title == null || title.isBlank() ? "web-extract" : title)
                        .replaceAll("[^A-Za-z0-9._-]+", "-")
                        .replaceAll("^-|-$", "");
        return (base.isBlank() ? "web-extract" : base.substring(0, Math.min(base.length(), 80)))
                + "-"
                + sha256(url).substring(0, 12)
                + ".md";
    }

    /**
     * 生成当前操作所需的sha256文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String sha256(String value) {
        return DigestUtils.sha256Hex(value);
    }

    /**
     * Web提取工具内部的提取结果，封装该步骤需要的状态或输入输出。
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Extracted {
        /**
         * 当前提取结果的可读标题，供宿主界面展示。
         */
        @Getter(AccessLevel.PACKAGE)
        private final String title;

        /**
         * 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
         */
        @Getter(AccessLevel.PACKAGE)
        private final String content;
    }
}
