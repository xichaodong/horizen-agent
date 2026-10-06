package dev.horizen.agent.sandbox.e2b.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.common.process.ShellQuoteUtils;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionApplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 使用 E2B 管理面和普通 JSON 同步 envd 协议的 AgentScope 沙箱。
 */
public final class HttpE2bSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {
    /**
     * 当前组件的诊断日志器。
     */
    private static final Logger log = LoggerFactory.getLogger(HttpE2bSandbox.class);

    /**
     * 传输CHUNK字节的固定取值，用于相应策略和边界判断。
     */
    private static final int TRANSFER_CHUNK_BYTES = 65536;

    /**
     * 归档集合的固定取值，用于相应策略和边界判断。
     */
    private static final Semaphore ARCHIVES = new Semaphore(2);

    /**
     * 当前工作状态或状态存储对象，供执行与恢复流程使用。
     */
    private final HttpE2bSandboxState state;

    /**
     * 沙箱控制面客户端，负责创建、恢复与销毁执行环境。
     */
    private final E2bPlatformClient platform;

    /**
     * 沙箱运行端命令与文件传输客户端。
     */
    private final EnvdSyncProcessClient envd;

    /**
     * 可供当前请求选择的选项或策略集合。
     */
    private final HttpE2bSandboxClientOptions options;

    /**
     * 检查点Saved的状态标记，用于选择当前组件的处理路径。
     */
    private boolean checkpointSaved;

    /**
     * initialized的状态标记，用于选择当前组件的处理路径。
     */
    private boolean initialized;

    /**
     * 创建HTTP2B沙箱，初始化该组件所需的状态、配置或依赖。
     *
     * @param state        当前工作状态或状态存储对象，供执行与恢复流程使用。
     * @param options      可供当前请求选择的选项或策略集合。
     * @param objectMapper 提供对象映射器能力的依赖，具体实现由当前组件的组装方传入。
     */
    public HttpE2bSandbox(
            HttpE2bSandboxState state,
            HttpE2bSandboxClientOptions options,
            ObjectMapper objectMapper) {
        super(state);
        this.state = state;
        this.options = options;
        this.platform = new E2bPlatformClient(options, objectMapper);
        this.envd = new EnvdSyncProcessClient(options, objectMapper);
    }

    /**
     * 向远端控制面创建或恢复当前执行使用的沙箱，并绑定所选隔离身份。
     *
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void start() throws Exception {
        checkpointSaved = false;
        initialized = false;
        ensureSandbox();
        if (state.isSnapshotCommitted()
                && (state.getSnapshot() == null || !state.getSnapshot().isRestorable())) {
            throw new IOException("Previously committed workspace snapshot is unavailable");
        }
        super.start();
        if (options.isRefreshPublishedWorkspace()) {
            // 任务归档可能包含旧声明。仅移除发布版本管理的根目录，再重新写入已选执行内容，
            // 之后才暴露工具。
            String script =
                    "import os,shutil\nroot=os.path.realpath("
                            + pythonString(getWorkspaceRoot())
                            + ")\n"
                            + "for name in ['AGENTS.md','knowledge','subagents','skills','.skills-cache']:\n"
                            + " p=os.path.join(root,name)\n"
                            + " if os.path.islink(p) or os.path.isfile(p): os.unlink(p)\n"
                            + " elif os.path.isdir(p): shutil.rmtree(p)\n";
            if (!doExec(
                    null,
                    "python3 -c " + shellQuote(script),
                    options.getSnapshotTimeoutSeconds())
                    .ok()) {
                throw new IOException("Failed to refresh published workspace");
            }
            var projection = WorkspaceProjectionApplier.build(state.getWorkspaceSpec());
            if (projection != null && projection.fileCount() > 0) {
                try (InputStream archive = new ByteArrayInputStream(projection.tarBytes())) {
                    doHydrateWorkspace(archive);
                }
                state.setWorkspaceProjectionHash(projection.hash());
            }
        }
        if (state.getSnapshot() != null
                && state.getSnapshot().isPersistenceEnabled()
                && state.getSnapshotSpec() != null) {
            state.setSnapshot(state.getSnapshotSpec().build("workspace-" + UUID.randomUUID()));
            state.setSnapshotCommitted(false);
        }
        initialized = true;
    }

    /**
     * 停止当前沙箱执行资源，按生命周期策略清理相关句柄。
     */
    @Override
    public void stop() throws Exception {
        // 创建阶段失败时 Harness 仍会执行 release；此时没有远端实例可供快照。
        if (!hasText(state.getSandboxId()) || checkpointSaved || !initialized) {
            return;
        }
        if (state.isSnapshotCommitted() && state.getSnapshotSpec() != null) {
            state.setSnapshot(state.getSnapshotSpec().build("workspace-" + UUID.randomUUID()));
            state.setSnapshotCommitted(false);
        }
        super.stop();
        checkpointSaved = true;
        if (state.getSnapshot() != null && state.getSnapshot().isPersistenceEnabled()) {
            state.setSnapshotCommitted(true);
        }
    }

    /**
     * 完成当前操作的shutdown步骤，按实现更新相应状态或依赖。
     */
    @Override
    public void shutdown() throws Exception {
        if (state.isSandboxOwned() && hasText(state.getSandboxId())) {
            platform.delete(state.getSandboxId());
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bSandbox处理步骤使用。
     *
     * @param runtimeContext 当前HTTP2B沙箱持有的运行时上下文对象，供相应处理步骤使用。
     * @param command        当前HTTP2B沙箱使用的命令，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     * @return 本次操作返回的Exec结果结果。
     */
    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        checkpointSaved = false;
        return envd.runShell(state, getWorkspaceRoot(), command, timeoutSeconds);
    }

    /**
     * 将沙箱工作目录打包并交给已配置的持久快照能力。
     *
     * @return 本次操作返回的输入事件流结果。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        if (!ARCHIVES.tryAcquire()) throw new IOException("workspace snapshot capacity exhausted");
        Path spool = null;
        String remote = "/tmp/horizen-snapshot-" + UUID.randomUUID() + ".tar";
        long deadline = deadline();
        try {
            // 暂存归档位于工作区外，避免将归档自身再次打包。
            String command =
                    "(ulimit -f "
                            + (options.getMaxSnapshotBytes() / 1024 + 1)
                            + "; tar --format=posix -cf "
                            + shellQuote(remote)
                            + " -C "
                            + shellQuote(getWorkspaceRoot())
                            + " .) && python3 -c "
                            + shellQuote(
                            "import os; print(os.path.getsize("
                                    + pythonString(remote)
                                    + "))");
            long size =
                    Long.parseLong(
                            envd.runShell(state, getWorkspaceRoot(), command, remaining(deadline))
                                    .stdout()
                                    .strip());
            if (size < 0 || size > options.getMaxSnapshotBytes())
                throw new IOException("workspace snapshot exceeds byte limit");
            spool = Files.createTempFile("horizen-e2b-snapshot-", ".tar");
            try (OutputStream output = Files.newOutputStream(spool)) {
                for (long offset = 0; offset < size; offset += TRANSFER_CHUNK_BYTES) {
                    int length = (int) Math.min(TRANSFER_CHUNK_BYTES, size - offset);
                    String python =
                            "f=open("
                                    + pythonString(remote)
                                    + ",'rb'); f.seek("
                                    + offset
                                    + "); import sys; sys.stdout.buffer.write(f.read("
                                    + length
                                    + "))";
                    byte[] chunk =
                            envd.runShellBinaryStdout(
                                    state,
                                    getWorkspaceRoot(),
                                    "python3 -c " + shellQuote(python),
                                    remaining(deadline),
                                    length);
                    if (chunk.length != length)
                        throw new IOException("incomplete workspace snapshot transfer");
                    output.write(chunk);
                }
            }
            WorkspaceArchiveValidator.validate(
                    spool, options.getMaxSnapshotBytes(), options.getMaxSnapshotEntries());
            Path owned = spool;
            InputStream input = Files.newInputStream(spool);
            spool = null;
            return new FilterInputStream(input) {
                /** 组件是否已关闭，用于避免重复释放或继续接收新工作。 */
                private boolean closed;

                /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
                @Override
                public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try {
                        super.close();
                    } finally {
                        try {
                            Files.deleteIfExists(owned);
                        } finally {
                            ARCHIVES.release();
                        }
                    }
                }
            };
        } catch (Exception error) {
            try {
                if (spool != null) Files.deleteIfExists(spool);
            } finally {
                ARCHIVES.release();
            }
            throw error;
        } finally {
            cleanup(remote);
        }
    }

    /**
     * 把已持久的工作区快照恢复到本次执行目录。
     *
     * @param archive 当前HTTP2B沙箱持有的归档对象，供相应处理步骤使用。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        checkpointSaved = false;
        if (!ARCHIVES.tryAcquire()) throw new IOException("workspace snapshot capacity exhausted");
        Path spool = null;
        String remote = "/tmp/horizen-hydrate-" + UUID.randomUUID() + ".tar";
        long deadline = deadline();
        try {
            spool = Files.createTempFile("horizen-e2b-hydrate-", ".tar");
            try (OutputStream output = Files.newOutputStream(spool)) {
                byte[] buffer = new byte[TRANSFER_CHUNK_BYTES];
                long size = 0;
                int length;
                while ((length = archive.read(buffer)) != -1) {
                    remaining(deadline);
                    size += length;
                    if (size > options.getMaxSnapshotBytes())
                        throw new IOException("workspace archive exceeds byte limit");
                    output.write(buffer, 0, length);
                }
            }
            WorkspaceArchiveValidator.validate(
                    spool, options.getMaxSnapshotBytes(), options.getMaxSnapshotEntries());
            try (InputStream input = Files.newInputStream(spool)) {
                byte[] buffer = new byte[TRANSFER_CHUNK_BYTES];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    String encoded =
                            Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, length));
                    String python =
                            "import base64; open("
                                    + pythonString(remote)
                                    + ",'ab').write(base64.b64decode("
                                    + pythonString(encoded)
                                    + "))";
                    envd.runShell(
                            state,
                            getWorkspaceRoot(),
                            "python3 -c " + shellQuote(python),
                            remaining(deadline));
                }
            }
            // 同时检查真实路径，避免沙箱内已有文件重定向归档解压位置。
            String python =
                    "import os,tarfile\nroot=os.path.realpath("
                            + pythonString(getWorkspaceRoot())
                            + ")\nwith tarfile.open("
                            + pythonString(remote)
                            + ", 'r:') as t:\n"
                            + " for m in t.getmembers():\n"
                            + "  p=os.path.realpath(os.path.join(root,m.name))\n"
                            + "  if os.path.commonpath([root,p]) != root: raise ValueError('archive escapes"
                            + " workspace')\n"
                            + "  if m.issym():\n"
                            + "   q=os.path.realpath(os.path.join(os.path.dirname(p),m.linkname))\n"
                            + "   if os.path.commonpath([root,q]) != root: raise ValueError('symlink escapes"
                            + " workspace')\n"
                            + " for m in t.getmembers(): t.extract(m,root,set_attrs=False)\n"
                            + " for m in reversed(t.getmembers()):\n"
                            + "  if not m.issym(): os.chmod(os.path.join(root,m.name),m.mode & 0o777)\n";
            envd.runShell(
                    state,
                    getWorkspaceRoot(),
                    "python3 -c " + shellQuote(python),
                    remaining(deadline));
        } finally {
            cleanup(remote);
            try {
                if (spool != null) Files.deleteIfExists(spool);
            } finally {
                ARCHIVES.release();
            }
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bSandbox处理步骤使用。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @return 本次操作返回的长整型结果。
     */
    private long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(options.getSnapshotTimeoutSeconds());
    }

    /**
     * 计算或取得本方法声明的结果，供当前HttpE2bSandbox处理步骤使用。
     * 内部等待时限使用单调时钟计算，不依赖墙上时间的跳变。
     *
     * @param deadline 当前HTTP2B沙箱使用的截止，供其处理与状态记录使用。
     * @return 本次操作返回的整数结果。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static int remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("workspace snapshot transfer timed out");
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos));
    }

    /**
     * 把当前输入编码为 JSON 文本，供协议输出或持久化保存使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String pythonString(String value) throws IOException {
        return JsonUtils.newMapper().writeValueAsString(value);
    }

    /**
     * 收敛当前沙箱使用状态并清理本对象持有的资源。
     *
     * @param remote 当前HTTP2B沙箱使用的远端，供其处理与状态记录使用。
     */
    private void cleanup(String remote) {
        try {
            envd.runShell(state, "/", "rm -f -- " + shellQuote(remote), 5);
        } catch (Exception error) {
            log.debug("Snapshot staging cleanup failed ({})", error.getClass().getSimpleName());
        }
    }

    /**
     * 完成当前操作的doSetupWorkspace步骤，按实现更新相应状态或依赖。
     */
    @Override
    protected void doSetupWorkspace() throws Exception {
        // 首次启动时工作区本身尚不存在，不能把它作为创建命令的 cwd。
        envd.runShell(state, "/", "mkdir -p " + shellQuote(getWorkspaceRoot()), 30);
    }

    /**
     * 按执行生命周期销毁远端工作区，避免保留旧执行环境。
     */
    @Override
    protected void doDestroyWorkspace() throws Exception {
        try {
            envd.runShell(
                    state, getWorkspaceRoot(), "rm -rf " + shellQuote(getWorkspaceRoot()), 30);
        } catch (Exception error) {
            log.debug("清理沙箱工作区失败：{}", error.getMessage());
        }
    }

    /**
     * 读取工作区根。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    protected String getWorkspaceRoot() {
        return state.getWorkspaceRoot();
    }

    /**
     * 判断是否支持文件传输。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean supportsFileTransfer(String path) {
        try {
            transferPath(path);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /**
     * 校验文件传输路径属于当前沙箱允许的范围。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private String transferPath(String value) {
        if (value == null || value.indexOf(0) >= 0 || value.contains("\\"))
            throw new IllegalArgumentException("Invalid sandbox file path");
        Path root = Path.of(getWorkspaceRoot()).normalize();
        Path path = Path.of(value);
        path = (path.isAbsolute() ? path : root.resolve(path)).normalize();
        if (!path.startsWith(root) || path.equals(root))
            throw new IllegalArgumentException("Sandbox transfer must stay inside the workspace");
        return path.toString();
    }

    /**
     * 下载文件。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次处理取得或生成的内容字节。
     */
    @Override
    public byte[] downloadFile(String path) throws Exception {
        return envd.runShellBinaryStdout(
                state, getWorkspaceRoot(), "cat -- " + shellQuote(transferPath(path)), 60);
    }

    /**
     * 在传输容量和路径约束内向沙箱写入输入文件。
     *
     * @param path    需要读取、写入或校验的路径。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void uploadFile(String path, byte[] content) throws Exception {
        if (content.length > 32 * 1024 * 1024)
            throw new IOException("Sandbox upload exceeds 32 MiB");
        String target = transferPath(path);
        String staging = target + ".upload-" + UUID.randomUUID();
        checkpointSaved = false;
        try {
            String prepare =
                    "import os; p="
                            + pythonString(staging)
                            + "; os.makedirs(os.path.dirname(p),exist_ok=True); open(p,'wb').close()";
            envd.runShell(state, getWorkspaceRoot(), "python3 -c " + shellQuote(prepare), 60);
            for (int offset = 0; offset < content.length; offset += TRANSFER_CHUNK_BYTES) {
                String encoded =
                        Base64.getEncoder()
                                .encodeToString(
                                        Arrays.copyOfRange(
                                                content,
                                                offset,
                                                Math.min(
                                                        content.length,
                                                        offset + TRANSFER_CHUNK_BYTES)));
                String append =
                        "import base64; open("
                                + pythonString(staging)
                                + ",'ab').write(base64.b64decode("
                                + pythonString(encoded)
                                + "))";
                envd.runShell(state, getWorkspaceRoot(), "python3 -c " + shellQuote(append), 60);
            }
            envd.runShell(
                    state,
                    getWorkspaceRoot(),
                    "mv -- " + shellQuote(staging) + " " + shellQuote(target),
                    60);
        } finally {
            envd.runShell(state, getWorkspaceRoot(), "rm -f -- " + shellQuote(staging), 5);
        }
    }

    /**
     * 取得已启动的沙箱，尚未准备好时拒绝执行命令或文件操作。
     */
    private void ensureSandbox() throws Exception {
        if (!hasText(state.getSandboxId())) {
            platform.applyIdentity(state, platform.create());
            requireIdentity();
            return;
        }
        try {
            platform.applyIdentity(state, platform.restore(state.getSandboxId()));
            requireIdentity();
        } catch (Exception restoreFailure) {
            log.info("恢复原沙箱失败，创建新沙箱并从快照恢复工作区：{}", restoreFailure.getClass().getSimpleName());
            state.setWorkspaceRootReady(false);
            state.setWorkspaceProjectionHash(null);
            platform.applyIdentity(state, platform.create());
            requireIdentity();
        }
    }

    /**
     * 核对调用上下文中的隔离身份与当前沙箱归属一致。
     *
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireIdentity() {
        if (!hasText(state.getSandboxId()) || !hasText(state.getAccessToken())) {
            throw new IllegalStateException("沙箱响应缺少 sandboxID 或 envdAccessToken");
        }
    }

    /**
     * 判断是否存在文本。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 生成当前操作所需的shellQuote文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String shellQuote(String value) {
        return ShellQuoteUtils.quote(value);
    }
}
