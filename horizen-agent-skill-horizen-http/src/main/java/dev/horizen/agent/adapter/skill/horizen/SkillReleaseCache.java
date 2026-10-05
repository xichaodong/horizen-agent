package dev.horizen.agent.adapter.skill.horizen;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.skill.SkillReleaseManifest;
import dev.horizen.agent.skill.SkillReleaseSnapshot;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 以 releaseHash 为键的不可变磁盘缓存，用于下载优化和进程重启恢复。 */
public final class SkillReleaseCache {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 清单文件使用的固定标识或协议文本。 */
    private static final String MANIFEST_FILE = "release.json";

    /** 本组件使用的根路径或根对象，限定后续读取与定位范围。 */
    private final Path root;

    /** 最大文件的数量，供运行统计或容量控制使用。 */
    private final int maxFileCount;

    /** 最大Unpacked的字节数，用于容量或传输限制。 */
    private final long maxUnpackedBytes;

    /** 归档解压允许的最大膨胀比例，用于限制异常压缩内容。 */
    private final int maxCompressionRatio;

    /**
     * 创建Skill发布缓存，初始化该组件所需的状态、配置或依赖。
     *
     * @param root 当前操作允许使用的根路径。
     * @param maxFileCount 最大文件的数量，供运行统计或容量控制使用。
     * @param maxUnpackedBytes 最大Unpacked的字节数，用于容量或传输限制。
     * @param maxCompressionRatio 当前Skill发布缓存使用的最大压缩Ratio，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SkillReleaseCache(
            Path root, int maxFileCount, long maxUnpackedBytes, int maxCompressionRatio) {
        this.root = root.toAbsolutePath().normalize();
        this.maxFileCount = maxFileCount;
        this.maxUnpackedBytes = maxUnpackedBytes;
        this.maxCompressionRatio = maxCompressionRatio;
        if (maxFileCount <= 0 || maxUnpackedBytes <= 0 || maxCompressionRatio <= 0) {
            throw new IllegalArgumentException("Skill cache limits must be positive");
        }
    }

    /**
     * 下载并校验 Skill 发布制品，准备可供渐进读取的本地目录。
     *
     * @param manifest 当前Skill发布缓存持有的清单对象，供相应处理步骤使用。
     * @param client 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @return 本次操作返回的Skill发布快照结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized SkillReleaseSnapshot materialize(
            SkillReleaseManifest manifest, HorizenSkillReleaseClient client) {
        Optional<SkillReleaseSnapshot> cached = load(manifest.getReleaseHash());
        if (cached.isPresent()) {
            SkillReleaseSnapshot snapshot = cached.get();
            return snapshot.getManifest().equals(manifest)
                    ? snapshot
                    : snapshot.withManifest(manifest);
        }

        Path target = releaseDir(manifest.getReleaseHash());
        Path staging = root.resolve(manifest.getReleaseHash() + ".staging-" + UUID.randomUUID());
        Counters counters = new Counters();
        try {
            Files.createDirectories(staging.resolve("skills"));
            for (SkillReleaseManifest.Item item : manifest.getItems()) {
                extract(
                        client.download(item),
                        item,
                        staging.resolve("skills").resolve(item.getRuntimeName()),
                        counters);
            }
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(staging.resolve(MANIFEST_FILE).toFile(), manifest);
            Files.createDirectories(root);
            move(staging, target);
            return load(manifest.getReleaseHash()).orElseThrow();
        } catch (IOException | RuntimeException error) {
            deleteQuietly(staging);
            throw new IllegalStateException(
                    "Cannot materialize Horizen skill release " + manifest.getReleaseHash(), error);
        }
    }

    /**
     * 读取已准备发布中指定 Skill 的正文与资源。
     *
     * @param releaseHash 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Optional<SkillReleaseSnapshot> load(String releaseHash) {
        Path directory = releaseDir(releaseHash);
        Path manifestFile = directory.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        try {
            SkillReleaseManifest manifest =
                    JSON.readValue(manifestFile.toFile(), SkillReleaseManifest.class);
            if (!manifest.getReleaseHash().equals(releaseHash)) {
                throw new IllegalStateException(
                        "Cached release directory and manifest hash differ");
            }
            LinkedHashMap<String, SkillReleaseSnapshot.SnapshotSkill> skills =
                    new LinkedHashMap<>();
            for (SkillReleaseManifest.Item item : manifest.getItems()) {
                Path skillDirectory =
                        directory.resolve("skills").resolve(item.getRuntimeName()).normalize();
                if (!skillDirectory.startsWith(directory) || !Files.isDirectory(skillDirectory)) {
                    throw new IllegalStateException(
                            "Cached skill directory is missing: " + item.getRuntimeName());
                }
                Map<String, byte[]> files = readFiles(skillDirectory);
                byte[] markdown = files.remove("SKILL.md");
                if (markdown == null) {
                    throw new IllegalStateException(
                            "Cached skill has no SKILL.md: " + item.getRuntimeName());
                }
                AgentSkill skill =
                        SkillUtil.createFrom(
                                new String(markdown, StandardCharsets.UTF_8), Map.of(), "horizen");
                if (!item.getRuntimeName().equals(skill.getName())) {
                    throw new IllegalStateException(
                            "Skill name differs from Horizen runtimeName: "
                                    + item.getRuntimeName());
                }
                if (skills.put(
                                item.getRuntimeName(),
                                SkillReleaseSnapshot.skill(
                                        new String(markdown, StandardCharsets.UTF_8), files))
                        != null) {
                    throw new IllegalStateException(
                            "Duplicate runtimeName in release: " + item.getRuntimeName());
                }
            }
            return Optional.of(new SkillReleaseSnapshot(manifest, skills));
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Cannot load cached Horizen skill release " + releaseHash, error);
        }
    }

    /**
     * 读取该发布中的 Skill 视图集合。
     *
     * @return 本次处理得到的结果集合。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public List<SkillReleaseSnapshot> loadAll() {
        if (!Files.isDirectory(root)) return List.of();
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> Identifiers.SHA256.matcher(name).matches())
                    .sorted()
                    .map(this::load)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot list cached Horizen skill releases", error);
        }
    }

    /**
     * 在归档条目与容量限制内解包制品，不接受越过根目录的路径。
     *
     * @param archive 当前Skill发布缓存持有的归档对象，供相应处理步骤使用。
     * @param item 当前Skill发布缓存持有的条目对象，供相应处理步骤使用。
     * @param destination 当前Skill发布缓存持有的destination对象，供相应处理步骤使用。
     * @param counters 当前Skill发布缓存持有的统计对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void extract(
            byte[] archive, SkillReleaseManifest.Item item, Path destination, Counters counters)
            throws IOException {
        List<ArchiveEntry> entries = readEntries(archive, item, counters);
        String prefix = commonPrefix(entries);
        boolean skillFile = false;
        for (ArchiveEntry entry : entries) {
            String relative = entry.getName().substring(prefix.length());
            if (relative.isEmpty()) continue;
            requireSafeRelativePath(relative);
            Path target = destination.resolve(relative).normalize();
            if (!target.startsWith(destination)) {
                throw new IllegalStateException("Skill archive path escapes destination");
            }
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getContent());
            skillFile |= "SKILL.md".equals(relative);
        }
        if (!skillFile) {
            throw new IllegalStateException(
                    "Skill package has no root SKILL.md: " + item.getSkillKey());
        }
    }

    /**
     * 读取归档条目并核对它们的边界信息。
     *
     * @param archive 当前Skill发布缓存持有的归档对象，供相应处理步骤使用。
     * @param item 当前Skill发布缓存持有的条目对象，供相应处理步骤使用。
     * @param counters 当前Skill发布缓存持有的统计对象，供相应处理步骤使用。
     * @return 本次处理得到的结果集合。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private List<ArchiveEntry> readEntries(
            byte[] archive, SkillReleaseManifest.Item item, Counters counters) throws IOException {
        ArrayList<ArchiveEntry> entries = new ArrayList<>();
        try (ZipInputStream input =
                new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                requireSafeRelativePath(name);
                if (hasHiddenSegment(name)) continue;
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long size = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    counters.add(read);
                    if (entry.getCompressedSize() > 0
                            && size > entry.getCompressedSize() * (long) maxCompressionRatio) {
                        throw new IllegalStateException(
                                "Suspicious compression ratio: " + item.getSkillKey());
                    }
                    content.write(buffer, 0, read);
                }
                counters.file();
                entries.add(new ArchiveEntry(name, content.toByteArray()));
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("Skill package is empty: " + item.getSkillKey());
        }
        return entries;
    }

    /**
     * 从准备好的目录读取 Skill 正文与附属资源。
     *
     * @param directory 当前资源目录，供内容准备、读取与清理使用。
     * @return 按返回类型约定组织的结果映射。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private Map<String, byte[]> readFiles(Path directory) throws IOException {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("Symbolic links are forbidden in skill cache");
                }
                String relative = directory.relativize(path).toString().replace('\\', '/');
                requireSafeRelativePath(relative);
                files.put(relative, Files.readAllBytes(path));
            }
        }
        return files;
    }

    /**
     * 释放Dir。
     *
     * @param hash 内容或索引的摘要值，供去重、校验或缓存寻址使用。
     * @return 本次操作返回的路径结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private Path releaseDir(String hash) {
        if (hash == null || !Identifiers.SHA256.matcher(hash).matches()) {
            throw new IllegalArgumentException("Invalid releaseHash");
        }
        return root.resolve(hash);
    }

    /**
     * 生成当前操作所需的commonPrefix文本，供调用方继续处理。
     *
     * @param entries 条目集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理生成或读取的文本。
     */
    private static String commonPrefix(List<ArchiveEntry> entries) {
        String prefix = null;
        for (ArchiveEntry entry : entries) {
            int slash = entry.getName().indexOf('/');
            if (slash <= 0) return "";
            String candidate = entry.getName().substring(0, slash + 1);
            if (prefix == null) prefix = candidate;
            else if (!prefix.equals(candidate)) return "";
        }
        return prefix == null ? "" : prefix;
    }

    /**
     * 拒绝绝对路径、目录穿越与不符合发布资源规则的路径。
     *
     * @param path 需要读取、写入或校验的路径。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requireSafeRelativePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains(":")) {
            throw new IllegalStateException("Unsafe path in skill archive");
        }
        for (String segment : path.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalStateException("Unsafe path in skill archive");
            }
        }
    }

    /**
     * 判断是否存在Hidden执行段。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean hasHiddenSegment(String path) {
        for (String segment : path.split("/")) if (segment.startsWith(".")) return true;
        return false;
    }

    /**
     * 完成当前操作的move步骤，按实现更新相应状态或依赖。
     *
     * @param source 待解析或转换的来源对象。
     * @param target 本次转换、状态更新或内容写入的目标。
     */
    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        } catch (FileAlreadyExistsException ignored) {
            deleteQuietly(source);
        }
    }

    /**
     * 删除Quietly。
     *
     * @param root 当前操作允许使用的根路径。
     */
    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 临时目录清理采用尽力而为策略，遗留内容可由后续维护任务删除。
        }
    }

    /** Skill发布缓存内部的归档条目，封装该步骤需要的状态或输入输出。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ArchiveEntry {
        /** 当前归档条目的名称，用于目录、调用或展示中的识别。 */
        private String name;

        /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
        private byte[] content;
    }

    /** Skill发布缓存内部的统计，封装该步骤需要的状态或输入输出。 */
    private final class Counters {
        /** 当前工作区目录中的文件内容访问端口。 */
        private int files;

        /** 当前内容字节或字节计数，用于传输、校验与容量控制。 */
        private long bytes;

        /**
         * 完成当前操作的file步骤，按实现更新相应状态或依赖。
         * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
         */
        private void file() {
            if (++files > maxFileCount)
                throw new IllegalStateException("Release has too many files");
        }

        /**
         * 增加统计。
         *
         * @param count 当前统计或批次包含的项目数量。
         * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
         */
        private void add(int count) {
            bytes += count;
            if (bytes > maxUnpackedBytes)
                throw new IllegalStateException("Release is too large when unpacked");
        }
    }
}
