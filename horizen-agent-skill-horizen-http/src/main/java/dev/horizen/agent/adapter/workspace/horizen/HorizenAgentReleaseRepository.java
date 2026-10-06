package dev.horizen.agent.adapter.workspace.horizen;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.adapter.skill.horizen.HorizenSkillReleaseClient;
import dev.horizen.agent.adapter.skill.horizen.SkillReleaseCache;
import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.AgentReleaseRepository;
import dev.horizen.agent.domain.workspace.release.AgentReleaseSnapshot;
import dev.horizen.agent.domain.workspace.release.ReleaseManifestCanonicalizer;
import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;
import dev.horizen.agent.skill.SkillReleaseManifest;
import dev.horizen.agent.skill.SkillReleaseSnapshot;

import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 容量受限且可重建的缓存；租约在一次执行期间保护整个发布版本。
 */
public final class HorizenAgentReleaseRepository implements AgentReleaseRepository {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 读取发布描述并下载制品的 HTTP 客户端；本仓储不负责关闭调用方持有的客户端。
     */
    private final HorizenSkillReleaseClient client;

    /**
     * 本地发布缓存根目录，各版本按 releaseHash 使用独立目录。
     */
    private final Path root;

    /**
     * 所有已准备和预留发布允许占用的缓存容量上限，单位为字节。
     */
    private final long maxCacheBytes;

    /**
     * 本地受管工作区的发布目录读取端口；远端来源模式可以不提供。
     */
    private final WorkspaceCatalogRepository localCatalog;

    /**
     * 读取本地受管发布制品实际字节的内容仓储。
     */
    private final WorkspaceContentRepository localContents;

    /**
     * 保护版本索引、容量预留和租约计数的共享锁；网络下载在锁外执行。
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 最多同时接纳 17 个发布准备请求的许可，限制等待与准备任务数量。
     */
    private final Semaphore admission = new Semaphore(17);

    /**
     * 最多同时进行 4 个制品下载的许可，限制控制面与对象传输并发。
     */
    private final Semaphore downloads = new Semaphore(4);

    /**
     * 仍在准备的发布已预留的容量字节数，提交或失败后归还。
     */
    private long reservedBytes;

    /**
     * 仍在准备的发布已占用的缓存槽数量。
     */
    private int reservedSlots;

    /**
     * 单个完整发布允许准备的最大字节数，当前上限为 50 MiB。
     */
    private static final long MAX_RELEASE_BYTES = 50L * 1024 * 1024;

    /**
     * 发布内容哈希到准备条目的并发索引，已被租约引用的条目不能被驱逐。
     */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 一个发布版本的本地准备、容量预留与租约引用状态。
     */
    private static final class Entry {
        /**
         * 当前版本的准备锁，避免同一发布被重复下载或提交。
         */
        final ReentrantLock preparation = new ReentrantLock();

        /**
         * 当前进入该版本准备流程的使用者数量。
         */
        int users;

        /**
         * 仍持有当前发布内容快照的租约数量。
         */
        volatile int leases;

        /**
         * 当前准备流程预留的容量字节数。
         */
        long reservation;

        /**
         * 从该工作区发布中解析出的不可变 Skill 快照。
         */
        volatile SkillReleaseSnapshot skills;
    }

    /**
     * 创建HorizenAgent发布仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param client        当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @param root          当前操作允许使用的根路径。
     * @param maxCacheBytes 最大缓存的字节数，用于容量或传输限制。
     */
    public HorizenAgentReleaseRepository(
            HorizenSkillReleaseClient client, Path root, long maxCacheBytes) {
        this(client, root, maxCacheBytes, null, null);
    }

    /**
     * 创建HorizenAgent发布仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param client        当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @param root          当前操作允许使用的根路径。
     * @param maxCacheBytes 最大缓存的字节数，用于容量或传输限制。
     * @param catalog       当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param contents      资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public HorizenAgentReleaseRepository(
            HorizenSkillReleaseClient client,
            Path root,
            long maxCacheBytes,
            WorkspaceCatalogRepository catalog,
            WorkspaceContentRepository contents) {
        if (client == null && (catalog == null || contents == null))
            throw new IllegalArgumentException("Workspace publication provider is required");
        this.client = client;
        this.root = root.toAbsolutePath().normalize();
        this.maxCacheBytes = maxCacheBytes;
        this.localCatalog = catalog;
        this.localContents = contents;
        if (maxCacheBytes < 50L * 1024 * 1024)
            throw new IllegalArgumentException("Publication cache must hold at least 50 MiB");
    }

    /**
     * 取得目录当前发布，并校验其 Project 与 Agent 归属。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<AgentReleaseManifest> findCurrent(AgentCatalogKey catalog) {
        if (localCatalog != null)
            return localCatalog
                    .current(catalog.getProjectId(), catalog.getAgentKey())
                    .flatMap(r -> parse(catalog, localManifest(r)));
        return parse(catalog, client.fetchWorkspace(catalog.getProjectId(), catalog.getAgentKey()));
    }

    /**
     * 在原目录中取得会话绑定的指定发布，不回退到其他版本。
     *
     * @param catalog   当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param releaseId 发布记录标识，用于取得会话绑定的具体发布快照。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     * @throws SecurityException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Optional<AgentReleaseManifest> findById(AgentCatalogKey catalog, long releaseId) {
        Optional<AgentReleaseManifest> result =
                parse(
                        catalog,
                        localCatalog == null
                                ? client.fetchWorkspaceVersion(
                                catalog.getProjectId(), catalog.getAgentKey(), releaseId)
                                : localCatalog
                                .release(
                                        catalog.getProjectId(),
                                        catalog.getAgentKey(),
                                        releaseId)
                                .map(this::localManifest)
                                .orElse(JSON.nullNode()));
        if (result.isPresent() && result.get().getReleaseId() != releaseId) {
            throw new SecurityException("Publication releaseId mismatch");
        }
        return result;
    }

    /**
     * 从受管本地目录生成发布描述。
     *
     * @param release 当前HorizenAgent发布仓储持有的发布对象，供相应处理步骤使用。
     * @return 本次操作返回的JSON节点结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private JsonNode localManifest(WorkspaceCatalogRepository.Release release) {
        try {
            var node = (ObjectNode) JSON.readTree(release.getManifestJson());
            node.put("releaseId", release.getId());
            node.put("releaseNo", release.getReleaseNo());
            if (node.path("skillRelease").isObject())
                ((ObjectNode) node.path("skillRelease")).put("releaseId", release.getId());
            return node;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot decode Agent workspace release", error);
        }
    }

    /**
     * 在下载预算与容量上限内取得制品，并验证内容校验值。
     *
     * @param asset 当前HorizenAgent发布仓储持有的制品对象，供相应处理步骤使用。
     * @return 本次处理取得或生成的内容字节。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private byte[] downloadAsset(AgentReleaseManifest.Asset asset) {
        if (localContents == null || !asset.getUrl().startsWith("workspace-object:")) {
            if (client == null)
                throw new IllegalStateException(
                        "Legacy HTTP assets require the remote publication provider");
            return client.downloadAsset(asset.getUrl(), asset.getSha256(), asset.getSize());
        }
        byte[] bytes =
                localContents.download(
                        asset.getUrl().substring("workspace-object:".length()), 10L * 1024 * 1024);
        if (bytes.length != asset.getSize() || !hash(bytes).equals(asset.getSha256()))
            throw new IllegalStateException("Workspace asset integrity mismatch");
        return bytes;
    }

    /**
     * 解析远端发布描述并校验归属与必要字段。
     *
     * @param catalog 当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param n       当前HorizenAgent发布仓储持有的n对象，供相应处理步骤使用。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws SecurityException        当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private Optional<AgentReleaseManifest> parse(AgentCatalogKey catalog, JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return Optional.empty();
        List<AgentReleaseManifest.Asset> assets = new ArrayList<>();
        if (!n.path("assets").isArray())
            throw new IllegalStateException("Missing workspace assets");
        n.path("assets")
                .forEach(
                        a ->
                                assets.add(
                                        new AgentReleaseManifest.Asset(
                                                a.path("path").asText(),
                                                a.path("url").asText(),
                                                a.path("sha256").asText(),
                                                a.path("size").asLong(-1),
                                                a.path("mediaType").asText())));
        if ("workspace-files-v1".equals(n.path("format").asText())) {
            var manifest =
                    new AgentReleaseManifest(
                            n.path("projectId").asLong(),
                            n.path("agentKey").asText(),
                            n.path("releaseId").asLong(),
                            n.path("releaseNo").asLong(),
                            n.path("releaseHash").asText(),
                            assets);
            if (manifest.getProjectId() != catalog.getProjectId()
                    || !manifest.getAgentKey().equals(catalog.getAgentKey()))
                throw new SecurityException("Workspace scope mismatch");
            ReleaseManifestCanonicalizer.verify(manifest);
            return Optional.of(manifest);
        }
        JsonNode s = n.path("skillRelease");
        List<SkillReleaseManifest.Item> items = new ArrayList<>();
        if (!s.path("items").isArray()) throw new IllegalStateException("Missing workspace skills");
        s.path("items")
                .forEach(
                        i ->
                                items.add(
                                        new SkillReleaseManifest.Item(
                                                i.path("skillId").asLong(),
                                                i.path("skillKey").asText(),
                                                i.path("versionId").asLong(),
                                                i.path("version").asText(),
                                                i.path("runtimeName").asText(),
                                                i.path("packageUrl").asText(),
                                                i.path("sha256").asText(),
                                                i.path("packageSize").asLong(),
                                                i.path("minAgentVersion").asText())));
        if (items.size() > 100) throw new IllegalArgumentException("Too many published skills");
        SkillReleaseManifest skills =
                new SkillReleaseManifest(
                        s.path("projectId").asLong(),
                        s.path("releaseId").asLong(),
                        s.path("releaseNo").asLong(),
                        s.path("releaseHash").asText(),
                        s.path("skillCount").asInt(-1),
                        items);
        AgentReleaseManifest manifest =
                new AgentReleaseManifest(
                        n.path("projectId").asLong(),
                        n.path("agentKey").asText(),
                        n.path("releaseId").asLong(),
                        n.path("releaseNo").asLong(),
                        n.path("releaseHash").asText(),
                        assets,
                        skills);
        if (manifest.getProjectId() != catalog.getProjectId()
                || !manifest.getAgentKey().equals(catalog.getAgentKey()))
            throw new SecurityException("Publication scope mismatch");
        ReleaseManifestCanonicalizer.verify(manifest);
        return Optional.of(manifest);
    }

    /**
     * 为指定发布取得已验证的内容快照租约，准备中的版本不能被缓存清理移除。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param manifest 当前HorizenAgent发布仓储持有的清单对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent发布快照结果。
     * @throws IOException              当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public AgentReleaseSnapshot acquire(AgentReleaseManifest manifest) {
        if (!admission.tryAcquire())
            throw new IllegalStateException("Publication preparation queue is full");
        Entry e = pin(manifest.getReleaseHash());
        boolean locked = false;
        try {
            locked = e.preparation.tryLock(120, TimeUnit.SECONDS);
            if (!locked) throw new IllegalStateException("Publication preparation timed out");
            Files.createDirectories(root);
            Path dir = root.resolve(manifest.getReleaseHash());
            if (Files.exists(dir)) {
                try {
                    validateFiles(dir, manifest);
                } catch (RuntimeException | IOException error) {
                    if (e.leases > 0)
                        throw new IllegalStateException(
                                "Active publication cache is damaged", error);
                    delete(dir);
                    e.skills = null;
                }
            }
            if (!Files.exists(dir)) {
                reserve(manifest.getReleaseHash(), e);
                if (!downloads.tryAcquire(30, TimeUnit.SECONDS))
                    throw new IllegalStateException("Publication download capacity exhausted");
                Path staging =
                        root.resolve(manifest.getReleaseHash() + ".staging-" + UUID.randomUUID());
                try (var budget = client == null ? null : client.budget(Duration.ofSeconds(120))) {
                    Files.createDirectories(staging.resolve("assets"));
                    for (var a : manifest.getAssets()) {
                        Path path = staging.resolve("assets").resolve(a.getPath());
                        Files.createDirectories(path.getParent());
                        Files.write(path, downloadAsset(a));
                        if (a.getPath().startsWith("subagents/")
                                && AgentSpecLoader.loadFromFile(path, staging.resolve("assets"))
                                == null)
                            throw new IllegalArgumentException("Invalid subagent declaration");
                    }
                    if (!manifest.isWorkspaceFiles())
                        new SkillReleaseCache(
                                staging.resolve("skill-cache"),
                                500,
                                30L * 1024 * 1024,
                                1000)
                                .materialize(manifest.getSkillRelease(), client);
                    writeIntegrity(staging);
                    if (size(staging) > 50L * 1024 * 1024)
                        throw new IllegalStateException("Published workspace exceeds 50 MiB");
                    lock.lock();
                    try {
                        Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
                        unreserve(e);
                    } finally {
                        lock.unlock();
                    }
                } finally {
                    downloads.release();
                    delete(staging);
                }
            }
            SkillReleaseSnapshot prepared = e.skills;
            if (prepared == null)
                prepared =
                        manifest.isWorkspaceFiles()
                                ? workspaceSkills(dir.resolve("assets"), manifest)
                                : new SkillReleaseCache(
                                dir.resolve("skill-cache"),
                                500,
                                30L * 1024 * 1024,
                                1000)
                                .load(manifest.getSkillRelease().getReleaseHash())
                                .orElseThrow()
                                .withManifest(manifest.getSkillRelease());
            Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis()));
            lock.lock();
            try {
                e.skills = prepared;
                e.leases++;
            } finally {
                lock.unlock();
            }
            return new AgentReleaseSnapshot(
                    manifest,
                    e.skills,
                    path -> {
                        var asset =
                                manifest.getAssets().stream()
                                        .filter(a -> a.getPath().equals(path))
                                        .findFirst()
                                        .orElseThrow();
                        Path file = dir.resolve("assets").resolve(path);
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                || Files.size(file) != asset.getSize()
                                || !hash(Files.readAllBytes(file)).equals(asset.getSha256()))
                            throw new IOException("Publication cache asset is missing or changed");
                        return Files.newInputStream(file);
                    },
                    () -> release(manifest.getReleaseHash(), e));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publication load interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot prepare publication", error);
        } finally {
            if (locked) e.preparation.unlock();
            lock.lock();
            try {
                unreserve(e);
                e.users--;
                removeIdle(manifest.getReleaseHash(), e);
            } finally {
                lock.unlock();
            }
            admission.release();
        }
    }

    /**
     * 增加该发布缓存条目的引用，防止正在使用的版本被回收。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param hash 内容或索引的摘要值，供去重、校验或缓存寻址使用。
     * @return 本次操作返回的条目结果。
     */
    private Entry pin(String hash) {
        lock.lock();
        try {
            Entry entry = entries.computeIfAbsent(hash, ignored -> new Entry());
            entry.users++;
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 归还快照租约并维护缓存引用与容量账目。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param hash  内容或索引的摘要值，供去重、校验或缓存寻址使用。
     * @param entry 当前HorizenAgent发布仓储持有的条目对象，供相应处理步骤使用。
     */
    private void release(String hash, Entry entry) {
        lock.lock();
        try {
            entry.leases--;
            if (entry.leases == 0 && entry.users == 0) entry.skills = null;
            removeIdle(hash, entry);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除空闲。
     *
     * @param hash  内容或索引的摘要值，供去重、校验或缓存寻址使用。
     * @param entry 当前HorizenAgent发布仓储持有的条目对象，供相应处理步骤使用。
     */
    private void removeIdle(String hash, Entry entry) {
        if (entry.users == 0 && entry.leases == 0) entries.remove(hash, entry);
    }

    /**
     * 仅在持有容量锁时调用；预留空间包含正在暂存的数据。
     */
    private void unreserve(Entry entry) {
        if (entry.reservation == 0) return;
        reservedBytes -= entry.reservation;
        reservedSlots--;
        entry.reservation = 0;
    }

    /**
     * 在开始下载前预留该版本允许占用的容量与缓存槽。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param selected      当前HorizenAgent发布仓储使用的selected，供其处理与状态记录使用。
     * @param selectedEntry 当前HorizenAgent发布仓储持有的selected条目对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void reserve(String selected, Entry selectedEntry) throws IOException {
        lock.lock();
        try {
            List<Path> cached;
            try (var dirs = Files.list(root)) {
                cached =
                        dirs.filter(
                                        p ->
                                                Identifiers.SHA256
                                                        .matcher(p.getFileName().toString())
                                                        .matches())
                                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                                .toList();
            }
            long total = 0;
            int abandonedSlots = 0;
            // 同时计入近期崩溃留下的数据；正在暂存的内容已由预留空间覆盖。
            try (var paths = Files.list(root)) {
                for (Path path :
                        paths.filter(
                                        p ->
                                                p.getFileName()
                                                        .toString()
                                                        .matches("[0-9a-f]{64}\\.staging-.*"))
                                .toList()) {
                    String hash = path.getFileName().toString().substring(0, 64);
                    Entry preparing = entries.get(hash);
                    if (preparing != null && preparing.reservation > 0) continue;
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis()
                            > 600_000) delete(path);
                    else {
                        total += size(path);
                        abandonedSlots++;
                    }
                }
            }
            for (Path directory : cached) total += size(directory);
            int count = cached.size() + abandonedSlots;
            for (Path directory : cached) {
                if (total + reservedBytes + MAX_RELEASE_BYTES <= maxCacheBytes
                        && count + reservedSlots < 32) break;
                String hash = directory.getFileName().toString();
                Entry entry = entries.get(hash);
                if (hash.equals(selected) || entry != null && (entry.users > 0 || entry.leases > 0))
                    continue;
                long removed = size(directory);
                delete(directory);
                entries.remove(hash);
                total -= removed;
                count--;
            }
            if (total + reservedBytes + MAX_RELEASE_BYTES > maxCacheBytes
                    || count + reservedSlots >= 32)
                throw new IllegalStateException(
                        "Publication cache is full with active leases or preparations");
            selectedEntry.reservation = MAX_RELEASE_BYTES;
            reservedBytes += MAX_RELEASE_BYTES;
            reservedSlots++;

        } finally {
            lock.unlock();
        }
    }

    /**
     * 核对发布目录中各制品的大小、路径与完整性信息。
     *
     * @param dir      当前HorizenAgent发布仓储持有的dir对象，供相应处理步骤使用。
     * @param manifest 当前HorizenAgent发布仓储持有的清单对象，供相应处理步骤使用。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void validateFiles(Path dir, AgentReleaseManifest manifest) throws IOException {
        if (size(dir) > 50L * 1024 * 1024
                || Files.size(dir.resolve("integrity.json")) > 1024 * 1024)
            throw new IOException("Publication cache exceeds limits");
        JsonNode checks = JSON.readTree(dir.resolve("integrity.json").toFile());
        int count = 0;
        try (var files = Files.walk(dir)) {
            for (Path p : files.toList()) {
                if (Files.isSymbolicLink(p)) throw new IOException("Symlink in publication cache");
                if (!Files.isRegularFile(p) || p.getFileName().toString().equals("integrity.json"))
                    continue;
                String key = dir.relativize(p).toString().replace('\\', '/');
                if (!hash(Files.readAllBytes(p)).equals(checks.path(key).asText()))
                    throw new IOException("Publication cache checksum mismatch");
                count++;
            }
        }
        if (count != checks.size()) throw new IOException("Missing publication cache files");
        for (var a : manifest.getAssets()) {
            Path p = dir.resolve("assets").resolve(a.getPath());
            if (Files.size(p) != a.getSize() || !hash(Files.readAllBytes(p)).equals(a.getSha256()))
                throw new IOException("Asset checksum mismatch");
        }
    }

    /**
     * 保存发布目录的校验信息，供后续复用确认内容完整。
     *
     * @param dir 当前HorizenAgent发布仓储持有的dir对象，供相应处理步骤使用。
     */
    private static void writeIntegrity(Path dir) throws IOException {
        Map<String, String> checks = new TreeMap<>();
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.filter(Files::isRegularFile).toList())
                checks.put(
                        dir.relativize(p).toString().replace('\\', '/'),
                        hash(Files.readAllBytes(p)));
        }
        JSON.writeValue(dir.resolve("integrity.json").toFile(), checks);
    }

    /**
     * 从已验证的工作区文件提取 Skill 正文和资源，保留工作区发布的身份。
     *
     * @param root     当前操作允许使用的根路径。
     * @param manifest 当前HorizenAgent发布仓储持有的清单对象，供相应处理步骤使用。
     * @return 本次操作返回的Skill发布快照结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static SkillReleaseSnapshot workspaceSkills(Path root, AgentReleaseManifest manifest)
            throws IOException {
        Map<String, SkillReleaseSnapshot.SnapshotSkill> views = new LinkedHashMap<>();
        List<SkillReleaseManifest.Item> descriptors = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        manifest.getAssets().stream()
                .filter(a -> a.getPath().matches("skills/[^/]+/SKILL\\.md"))
                .forEach(a -> names.add(a.getPath().split("/")[1]));
        for (String name : names) {
            String prefix = "skills/" + name + "/";
            Map<String, byte[]> resources = new LinkedHashMap<>();
            String markdown = null;
            StringBuilder canonical = new StringBuilder();
            long bytes = 0;
            for (var asset :
                    manifest.getAssets().stream()
                            .filter(a -> a.getPath().startsWith(prefix))
                            .sorted(Comparator.comparing(AgentReleaseManifest.Asset::getPath))
                            .toList()) {
                String relative = asset.getPath().substring(prefix.length());
                byte[] content = Files.readAllBytes(root.resolve(asset.getPath()));
                bytes += content.length;
                canonical.append(relative).append('\0').append(asset.getSha256()).append('\n');
                if (relative.equals("SKILL.md"))
                    markdown = new String(content, StandardCharsets.UTF_8);
                else resources.put(relative, content);
            }
            var skill = SkillUtil.createFrom(markdown, Map.of(), "workspace");
            if (!name.equals(skill.getName()))
                throw new IllegalArgumentException("Skill directory and SKILL.md name differ");
            views.put(name, SkillReleaseSnapshot.skill(markdown, resources));
            long identity =
                    Long.parseLong(hash(name.getBytes(StandardCharsets.UTF_8)).substring(0, 13), 16)
                            + 1;
            descriptors.add(
                    new SkillReleaseManifest.Item(
                            identity,
                            name,
                            manifest.getReleaseId(),
                            "R" + manifest.getReleaseNo(),
                            name,
                            "workspace-files:" + prefix,
                            hash(canonical.toString().getBytes(StandardCharsets.UTF_8)),
                            bytes,
                            ""));
        }
        return new SkillReleaseSnapshot(
                new SkillReleaseManifest(
                        manifest.getProjectId(),
                        manifest.getReleaseId(),
                        manifest.getReleaseNo(),
                        manifest.getReleaseHash(),
                        views.size(),
                        descriptors),
                views);
    }

    /**
     * 删除当前回收流程选中的本地缓存目录内容。
     *
     * @param path 需要读取、写入或校验的路径。
     */
    public static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /**
     * 累计目录中实际文件内容的字节数。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的长整型结果。
     */
    private static long size(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        try (var files = Files.walk(path)) {
            long sum = 0;
            for (Path p : files.filter(Files::isRegularFile).toList()) sum += Files.size(p);
            return sum;
        }
    }

    /**
     * 计算摘要HorizenAgent发布仓储。
     *
     * @param bytes 当前操作处理的内容字节。
     * @return 本次处理生成或读取的文本。
     */
    private static String hash(byte[] bytes) {
        return DigestUtils.sha256Hex(bytes);
    }
}
