package dev.horizen.agent.adapter.skill.horizen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.common.io.BoundedStreams;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.skill.SkillReleaseManifest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 从 Horizen 读取当前生效的发布信息和不可变 Skill 制品包。
 */
public final class HorizenSkillReleaseClient {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 清单上限的固定取值，用于相应策略和边界判断。
     */
    private static final int MANIFEST_LIMIT = 1_048_576;

    /**
     * 正文READERS的固定取值，用于相应策略和边界判断。
     */
    private static final ThreadPoolExecutor BODY_READERS =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(16),
                    task -> {
                        Thread thread = new Thread(task, "publication-body-reader");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new AbortPolicy());

    /**
     * 执行有界响应正文读取的工作资源。
     */
    private final ExecutorService bodyReaders;

    /**
     * 查询目录当前发布所使用的服务接口地址。
     */
    private final URI currentReleaseEndpoint;

    /**
     * 服务访问令牌，由宿主配置提供，用于请求认证。
     */
    private final String token;

    /**
     * 允许产物Hosts的去重集合，供成员查找或范围检查使用。
     */
    private final Set<String> allowedArtifactHosts;

    /**
     * 是否允许通过明文 HTTP 读取远端资源；由相应下载策略校验。
     */
    private final boolean allowHttp;

    /**
     * 超时的时间配置，供等待、调度或失效判断使用。
     */
    private final Duration timeout;

    /**
     * 最大包的字节数，用于容量或传输限制。
     */
    private final long maxPackageBytes;

    /**
     * 用于实际网络请求的共享 HTTP 客户端。
     */
    private final HttpClient http;

    /**
     * 当前请求预算的单调时钟截止点。
     */
    private final ThreadLocal<Long> budgetDeadline = new ThreadLocal<>();

    /**
     * HorizenSkill发布客户端内部的请求预算，封装该步骤需要的状态或输入输出。
     */
    public final class RequestBudget implements AutoCloseable {
        /**
         * 当前操作之前的状态、内容引用或作用域，供恢复或回收使用。
         */
        private final Long previous;

        /**
         * 创建请求预算，初始化该组件所需的状态、配置或依赖。
         * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
         *
         * @param duration 当前操作使用的时间预算或间隔。
         */
        private RequestBudget(Duration duration) {
            previous = budgetDeadline.get();
            budgetDeadline.set(System.nanoTime() + duration.toNanos());
        }

        /**
         * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
         */
        @Override
        public void close() {
            if (previous == null) budgetDeadline.remove();
            else budgetDeadline.set(previous);
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param duration 当前操作使用的时间预算或间隔。
     * @return 本次操作返回的请求预算结果。
     */
    public RequestBudget budget(Duration duration) {
        return new RequestBudget(duration);
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenSkillReleaseClient处理步骤使用。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @return 本次操作返回的耗时结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private Duration remainingTimeout() {
        Long deadline = budgetDeadline.get();
        long nanos =
                deadline == null
                        ? timeout.toNanos()
                        : Math.min(timeout.toNanos(), deadline - System.nanoTime());
        if (nanos <= 0)
            throw new IllegalStateException("Publication preparation deadline exceeded");
        return Duration.ofNanos(nanos);
    }

    /**
     * 创建HorizenSkill发布客户端，初始化该组件所需的状态、配置或依赖。
     *
     * @param currentReleaseEndpoint 当前HorizenSkill发布客户端持有的当前发布接口地址对象，供相应处理步骤使用。
     * @param token                  服务访问令牌，由宿主配置提供，用于请求认证。
     * @param allowedArtifactHosts   允许产物Hosts的去重集合，供成员查找或范围检查使用。
     * @param allowHttp              是否允许通过明文 HTTP 读取远端资源；由相应下载策略校验。
     * @param timeout                本次等待允许持续的最长时间。
     * @param maxPackageBytes        最大包的字节数，用于容量或传输限制。
     */
    public HorizenSkillReleaseClient(
            URI currentReleaseEndpoint,
            String token,
            Set<String> allowedArtifactHosts,
            boolean allowHttp,
            Duration timeout,
            long maxPackageBytes) {
        this(
                currentReleaseEndpoint,
                token,
                allowedArtifactHosts,
                allowHttp,
                timeout,
                maxPackageBytes,
                HttpClient.newBuilder()
                        .connectTimeout(timeout == null ? Duration.ofSeconds(5) : timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                BODY_READERS);
    }

    /**
     * 创建HorizenSkill发布客户端，初始化该组件所需的状态、配置或依赖。
     *
     * @param currentReleaseEndpoint 当前HorizenSkill发布客户端持有的当前发布接口地址对象，供相应处理步骤使用。
     * @param token                  服务访问令牌，由宿主配置提供，用于请求认证。
     * @param allowedArtifactHosts   允许产物Hosts的去重集合，供成员查找或范围检查使用。
     * @param allowHttp              是否允许通过明文 HTTP 读取远端资源；由相应下载策略校验。
     * @param timeout                本次等待允许持续的最长时间。
     * @param maxPackageBytes        最大包的字节数，用于容量或传输限制。
     * @param client                 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @param bodyReaders            提供正文Readers能力的依赖，具体实现由当前组件的组装方传入。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public HorizenSkillReleaseClient(
            URI currentReleaseEndpoint,
            String token,
            Set<String> allowedArtifactHosts,
            boolean allowHttp,
            Duration timeout,
            long maxPackageBytes,
            HttpClient client,
            ExecutorService bodyReaders) {
        this.currentReleaseEndpoint = requireUrl(currentReleaseEndpoint, allowHttp, Set.of());
        this.token = token == null ? "" : token.trim();
        this.allowedArtifactHosts =
                allowedArtifactHosts == null
                        ? Set.of()
                        : allowedArtifactHosts.stream()
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .filter(value -> !value.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        this.allowHttp = allowHttp;
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        this.maxPackageBytes = maxPackageBytes;
        if (this.timeout.isZero() || this.timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxPackageBytes <= 0) {
            throw new IllegalArgumentException("maxPackageBytes must be positive");
        }
        if (this.allowedArtifactHosts.isEmpty()) {
            throw new IllegalArgumentException("At least one artifact host must be allowed");
        }
        this.http = Objects.requireNonNull(client);
        this.bodyReaders = Objects.requireNonNull(bodyReaders);
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenSkillReleaseClient处理步骤使用。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Optional<SkillReleaseManifest> fetchCurrent(long projectId) {
        if (projectId <= 0) {
            throw new IllegalArgumentException("projectId must be positive");
        }
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(Map.of("projectId", projectId));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot encode Horizen skill release request", error);
        }
        HttpRequest.Builder request =
                HttpRequest.newBuilder(currentReleaseEndpoint)
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        JsonNode root;
        try {
            root = JSON.readTree(send(request.build(), MANIFEST_LIMIT, "skill release manifest"));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot decode Horizen skill release manifest", error);
        }
        if (root.path("code").asInt(0) != 200) {
            throw new IllegalStateException("Horizen rejected the skill release request");
        }
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            return Optional.empty();
        }
        if (data.path("projectId").asLong() != projectId) {
            throw new IllegalStateException("Horizen skill release projectId mismatch");
        }
        ArrayList<SkillReleaseManifest.Item> items = new ArrayList<>();
        if (!data.path("items").isArray()) {
            throw new IllegalStateException("Horizen skill release items are missing");
        }
        data.path("items")
                .forEach(
                        item ->
                                items.add(
                                        new SkillReleaseManifest.Item(
                                                item.path("skillId").asLong(),
                                                item.path("skillKey").asText(null),
                                                item.path("versionId").asLong(),
                                                item.path("version").asText(null),
                                                item.path("runtimeName").asText(null),
                                                item.path("packageUrl").asText(null),
                                                item.path("sha256").asText(null),
                                                item.path("packageSize").asLong(),
                                                item.path("minAgentVersion").asText(""))));
        return Optional.of(
                new SkillReleaseManifest(
                        projectId,
                        data.path("releaseId").asLong(),
                        data.path("releaseNo").asLong(),
                        data.path("releaseHash").asText(null),
                        data.path("skillCount").asInt(-1),
                        items));
    }

    /**
     * 下载HorizenSkill发布客户端。
     *
     * @param item 当前HorizenSkill发布客户端持有的条目对象，供相应处理步骤使用。
     * @return 本次处理取得或生成的内容字节。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public byte[] download(SkillReleaseManifest.Item item) {
        if (item.getPackageSize() > maxPackageBytes) {
            throw new IllegalStateException(
                    "Skill package exceeds configured size limit: " + item.getSkillKey());
        }
        URI uri = requireUrl(URI.create(item.getPackageUrl()), allowHttp, allowedArtifactHosts);
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Accept", "application/zip")
                        .GET()
                        .build();
        byte[] content = send(request, maxPackageBytes, "skill package " + item.getSkillKey());
        if (content.length != item.getPackageSize()) {
            throw new IllegalStateException("Skill package size mismatch: " + item.getSkillKey());
        }
        if (!sha256(content).equals(item.getSha256())) {
            throw new IllegalStateException(
                    "Skill package checksum mismatch: " + item.getSkillKey());
        }
        return content;
    }

    /**
     * 完整 Agent 工作区契约共用的容量受限传输实现。
     */
    public JsonNode fetchWorkspace(long projectId, String agentKey) {
        return fetchWorkspace(
                currentReleaseEndpoint, Map.of("projectId", projectId, "agentKey", agentKey));
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenSkillReleaseClient处理步骤使用。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey  宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param releaseId 发布记录标识，用于取得会话绑定的具体发布快照。
     * @return 本次操作返回的JSON节点结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public JsonNode fetchWorkspaceVersion(long projectId, String agentKey, long releaseId) {
        if (releaseId <= 0) throw new IllegalArgumentException("releaseId must be positive");
        return fetchWorkspace(
                currentReleaseEndpoint.resolve("by-id"),
                Map.of("projectId", projectId, "agentKey", agentKey, "releaseId", releaseId));
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenSkillReleaseClient处理步骤使用。
     *
     * @param endpoint 已解析的服务接口地址，供实际网络请求使用。
     * @param payload  当前HorizenSkill发布客户端持有的负载对象，供相应处理步骤使用。
     * @return 本次操作返回的JSON节点结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws SecurityException     当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private JsonNode fetchWorkspace(URI endpoint, Object payload) {
        try {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(timeout)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofByteArray(
                                            JSON.writeValueAsBytes(payload)));
            if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
            JsonNode root =
                    JSON.readTree(
                            send(request.build(), MANIFEST_LIMIT, "Agent workspace manifest"));
            if (root.path("code").asInt() == 401 || root.path("code").asInt() == 403)
                throw new SecurityException("Workspace authorization rejected");
            if (root.path("code").asInt() != 200)
                throw new IllegalStateException("Workspace manifest request rejected");
            return root.path("data");
        } catch (IOException error) {
            throw new IllegalStateException("Cannot decode workspace manifest", error);
        }
    }

    /**
     * 下载制品。
     *
     * @param url          资源或远端接口地址；具体访问范围由所属服务的配置校验。
     * @param expectedHash 预期的内容摘要，供校验或去重使用。
     * @param expectedSize 当前HorizenSkill发布客户端使用的预期大小，供其处理与状态记录使用。
     * @return 本次处理取得或生成的内容字节。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public byte[] downloadAsset(String url, String expectedHash, long expectedSize) {
        URI uri = requireUrl(URI.create(url), allowHttp, allowedArtifactHosts);
        byte[] bytes =
                send(
                        HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                        Math.min(expectedSize, maxPackageBytes),
                        "workspace asset");
        if (bytes.length != expectedSize || !sha256(bytes).equals(expectedHash))
            throw new IllegalStateException("Workspace asset checksum/size mismatch");
        return bytes;
    }

    /**
     * 发送HorizenSkill发布客户端。
     *
     * @param request 当前操作的请求参数。
     * @param limit   本次处理或返回数量上限。
     * @param label   当前HorizenSkill发布客户端使用的显示文本，供其处理与状态记录使用。
     * @return 本次处理取得或生成的内容字节。
     * @throws IOException           当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws SecurityException     当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private byte[] send(HttpRequest request, long limit, String label) {
        try {
            request =
                    HttpRequest.newBuilder(request, (name, value) -> true)
                            .timeout(remainingTimeout())
                            .build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new SecurityException("Workspace authorization rejected");
                throw new IllegalStateException(label + " returned HTTP " + response.statusCode());
            }
            try (InputStream input = response.body()) {
                Future<byte[]> read = null;
                try {
                    read = bodyReaders.submit(() -> readCapped(input, limit, label));
                    return read.get(remainingTimeout().toNanos(), TimeUnit.NANOSECONDS);
                } catch (TimeoutException error) {
                    throw new IllegalStateException(label + " body read timed out", error);
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw new IOException(label + " body read failed", error.getCause());
                } finally {
                    if (read != null && !read.isDone()) read.cancel(true);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " request was interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException(label + " request failed", error);
        }
    }

    /**
     * 读取受限。
     *
     * @param input 本次处理的输入。
     * @param limit 本次处理或返回数量上限。
     * @param label 当前HorizenSkill发布客户端使用的显示文本，供其处理与状态记录使用。
     * @return 本次处理取得或生成的内容字节。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static byte[] readCapped(InputStream input, long limit, String label)
            throws IOException {
        try {
            return BoundedStreams.read(input, limit);
        } catch (IOException error) {
            throw new IOException(label + " could not be read within configured size limit", error);
        }
    }

    /**
     * 取得并校验URL。
     *
     * @param uri          当前HorizenSkill发布客户端持有的URI对象，供相应处理步骤使用。
     * @param allowHttp    是否允许通过明文 HTTP 读取远端资源；由相应下载策略校验。
     * @param allowedHosts 允许访问的远端主机集合，供 URL 范围校验使用。
     * @return 本次操作返回的URI结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static URI requireUrl(URI uri, boolean allowHttp, Set<String> allowedHosts) {
        if (uri == null
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Skill release URL must be an absolute URL without credentials");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || allowHttp && "http".equals(scheme))) {
            throw new IllegalArgumentException("Skill release URL must use HTTPS");
        }
        if (!allowedHosts.isEmpty()
                && !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Skill package host is not allowed: " + uri.getHost());
        }
        return uri;
    }

    /**
     * 生成当前操作所需的sha256文本，供调用方继续处理。
     *
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次处理生成或读取的文本。
     */
    private static String sha256(byte[] content) {
        return DigestUtils.sha256Hex(content);
    }
}
