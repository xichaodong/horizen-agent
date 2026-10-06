package dev.horizen.agent.adapter.agentscope.workspace.filesystem;

import dev.horizen.agent.adapter.agentscope.workspace.snapshot.SandboxSnapshotCheckpoint;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxLease;

import org.apache.commons.compress.archivers.tar.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 提供与沙箱调用相同的云端 Session 目录视图，不暴露 Shell 执行能力。
 */
public final class SessionWorkspaceVolume implements AutoCloseable {
    /**
     * 本组件使用的根路径或根对象，限定后续读取与定位范围。
     */
    private final Path root;

    /**
     * 当前工作区目录中的文件内容访问端口。
     */
    private final LocalFilesystem files;

    /**
     * 保存或取得工作区归档内容的存储端口。
     */
    private final WorkspaceSnapshotRepository archives;

    /**
     * 按会话定位最近持久工作区快照的指针仓储。
     */
    private final WorkspaceSnapshotPointerRepository pointers;

    /**
     * 组合资源归属与作用域的定位键，供仓储查询和更新使用。
     */
    private final WorkspaceSnapshotKey key;

    /**
     * 当前操作取得的工作区或发布资源租约，使用结束后归还。
     */
    private final SandboxLease lease;

    /**
     * 本次处理或传输允许的最大字节数。
     */
    private final long maxBytes;

    /**
     * 归档或目录中允许处理的条目数量上限。
     */
    private final int maxEntries;

    /**
     * 当前工作区修改是否已保存到快照。
     */
    private boolean saved;

    /**
     * 组件是否已关闭，用于避免重复释放或继续接收新工作。
     */
    private boolean closed;

    /**
     * 基础快照的标识，用于关联相应记录或执行。
     */
    private String baseSnapshotId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 创建会话工作区工作卷，初始化该组件所需的状态、配置或依赖。
     *
     * @param root       当前操作允许使用的根路径。
     * @param archives   提供归档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param pointers   提供指针集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param key        当前对象的查找或写入键。
     * @param lease      当前会话工作区工作卷持有的租约对象，供相应处理步骤使用。
     * @param maxBytes   本次处理或传输允许的最大字节数。
     * @param maxEntries 归档或目录中允许处理的条目数量上限。
     */
    private SessionWorkspaceVolume(
            Path root,
            WorkspaceSnapshotRepository archives,
            WorkspaceSnapshotPointerRepository pointers,
            WorkspaceSnapshotKey key,
            SandboxLease lease,
            long maxBytes,
            int maxEntries) {
        this.root = root;
        this.files = new LocalFilesystem(root, true, 10);
        this.archives = archives;
        this.pointers = pointers;
        this.key = key;
        this.lease = lease;
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    /**
     * 准备会话工作区工作卷。
     *
     * @param call       当前会话工作区工作卷持有的调用对象，供相应处理步骤使用。
     * @param agent      当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param archives   提供归档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param pointers   提供指针集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param guard      当前会话工作区工作卷持有的防护对象，供相应处理步骤使用。
     * @param maxBytes   本次处理或传输允许的最大字节数。
     * @param maxEntries 归档或目录中允许处理的条目数量上限。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void prepare(
            RuntimeContext call,
            String agent,
            WorkspaceSnapshotRepository archives,
            WorkspaceSnapshotPointerRepository pointers,
            SandboxExecutionGuard guard,
            long maxBytes,
            int maxEntries) {
        SandboxLease lease = null;
        Path root = null;
        try {
            lease = guard.tryEnter(SessionWorkspaceIdentity.key(call, agent));
            root = Files.createTempDirectory("horizen-session-view-");
            WorkspaceSnapshotKey key =
                    new WorkspaceSnapshotKey(call.getUserId(), call.getSessionId());
            String id = pointers.findSnapshotId(key).orElse(null);
            if (id != null)
                try (InputStream input = archives.download(id)) {
                    extract(input, root, maxBytes, maxEntries);
                }
            var view =
                    new SessionWorkspaceVolume(
                            root, archives, pointers, key, lease, maxBytes, maxEntries);
            view.baseSnapshotId = id;
            var execution = call.get(ArtifactExecutionContext.class);
            view.turnId = execution == null ? null : execution.getTurnId();
            call.put(SessionWorkspaceVolume.class, view);
        } catch (Exception error) {
            try {
                if (root != null) deleteTree(root);
            } catch (IOException ignored) {
            }
            if (lease != null) lease.close();
            throw new IllegalStateException("Cannot restore Session workspace", error);
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前SessionWorkspaceVolume处理步骤使用。
     *
     * @return 本次操作返回的本地文件系统结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public LocalFilesystem filesystem() {
        if (closed) throw new IllegalStateException("Workspace view closed");
        return files;
    }

    /**
     * 完成当前操作的changed步骤，按实现更新相应状态或依赖。
     */
    public void changed() {
        saved = false;
    }

    /**
     * 完成当前操作的checkpoint步骤，按实现更新相应状态或依赖。
     *
     * @throws IOException           当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized void checkpoint() {
        if (saved || closed) return;
        Path spool = null;
        try {
            spool = Files.createTempFile("horizen-session-archive-", ".tar");
            try (var output = new TarArchiveOutputStream(Files.newOutputStream(spool));
                 var paths = Files.walk(root)) {
                output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                int count = 0;
                long bytes = 0;
                for (Path file : paths.filter(p -> !p.equals(root)).sorted().toList()) {
                    if (Files.isSymbolicLink(file))
                        throw new IOException("Workspace symlinks cannot be archived");
                    if (++count > maxEntries)
                        throw new IOException("Workspace entry limit exceeded");
                    if (Files.isRegularFile(file)) bytes += Files.size(file);
                    if (bytes > maxBytes) throw new IOException("Workspace archive exceeds limit");
                    var entry =
                            new TarArchiveEntry(
                                    file.toFile(),
                                    root.relativize(file).toString().replace('\\', '/'));
                    output.putArchiveEntry(entry);
                    if (Files.isRegularFile(file)) Files.copy(file, output);
                    output.closeArchiveEntry();
                }
            }
            if (Files.size(spool) > maxBytes)
                throw new IOException("Workspace archive exceeds limit");
            String id = "workspace-" + UUID.randomUUID();
            try (InputStream input = Files.newInputStream(spool)) {
                archives.upload(id, input);
            }
            if (!pointers.compareAndSetCommitted(key, baseSnapshotId, id, turnId))
                throw new IllegalStateException(
                        "Session workspace changed or execution lease was lost");
            baseSnapshotId = id;
            saved = true;
        } catch (Exception error) {
            throw new SandboxSnapshotCheckpoint.SnapshotCheckpointException(error);
        } finally {
            if (spool != null)
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException ignored) {
                }
        }
    }

    /**
     * 保存会话工作区工作卷。
     *
     * @param call 当前会话工作区工作卷持有的调用对象，供相应处理步骤使用。
     */
    public static void save(RuntimeContext call) {
        var view = call.get(SessionWorkspaceVolume.class);
        if (view != null) view.checkpoint();
    }

    /**
     * 释放会话工作区工作卷。
     *
     * @param call 当前会话工作区工作卷持有的调用对象，供相应处理步骤使用。
     */
    public static void release(RuntimeContext call) {
        var view = call.get(SessionWorkspaceVolume.class);
        if (view != null) view.close();
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     *
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            deleteTree(root);
        } catch (IOException error) {
            throw new IllegalStateException("Workspace view cleanup failed", error);
        } finally {
            lease.close();
        }
    }

    /**
     * 提取会话工作区工作卷。
     *
     * @param input      本次处理的输入。
     * @param root       当前操作允许使用的根路径。
     * @param maxBytes   本次处理或传输允许的最大字节数。
     * @param maxEntries 归档或目录中允许处理的条目数量上限。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void extract(InputStream input, Path root, long maxBytes, int maxEntries)
            throws IOException {
        try (var tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            long total = 0;
            int count = 0;
            byte[] buffer = new byte[65536];
            while ((entry = tar.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)
                        || entry.getName().startsWith("/")
                        || entry.getName().contains("\\")
                        || entry.isLink()
                        || entry.isSymbolicLink()
                        || !(entry.isDirectory() || entry.isFile()))
                    throw new IOException("Unsafe workspace entry");
                if (++count > maxEntries
                        || entry.getSize() < 0
                        || (total += entry.getSize()) > maxBytes)
                    throw new IOException("Workspace archive exceeds limits");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    int n;
                    while ((n = tar.read(buffer)) != -1) output.write(buffer, 0, n);
                }
            }
        }
    }

    /**
     * 删除Tree。
     *
     * @param root 当前操作允许使用的根路径。
     */
    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
