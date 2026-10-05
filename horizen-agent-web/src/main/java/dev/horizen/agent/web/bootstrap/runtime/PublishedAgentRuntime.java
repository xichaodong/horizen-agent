package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.application.workspace.AgentReleaseService;
import dev.horizen.agent.domain.workspace.release.AgentReleaseSnapshot;
import dev.horizen.agent.observability.horizen.HorizenTraceContext;
import dev.horizen.agent.runtime.api.AgentContextBinding;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.SessionExecutionState;
import dev.horizen.agent.runtime.api.SessionTurnBusyException;
import dev.horizen.agent.runtime.skill.SkillReleaseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/** 每次执行创建新的运行时对象图，避免残留声明和本地 Skill 覆盖。 */
public final class PublishedAgentRuntime implements AgentRuntime {
    /** 日志的固定取值，用于相应策略和边界判断。 */
    private static final Logger LOG = LoggerFactory.getLogger(PublishedAgentRuntime.class);

    /** 准备会话原发布与不可变工作区内容的服务。 */
    private final AgentReleaseService releases;

    /** 跨实例执行控制通道，将取消请求送到原执行持有方。 */
    private final AgentRuntime controls;

    /** 当前执行目录的根路径，承载恢复后的任务文件。 */
    private final Path executionRoot;

    /** 容量的并发准入许可，限制同时进行的处理数量。 */
    private final Semaphore capacity;

    /** 构造当前版本运行对象的工厂。 */
    private final BiFunction<AgentReleaseSnapshot, Path, HarnessAgentRuntime> factory;

    /** 活跃的索引映射，供按键查找或归并当前组件的数据。 */
    private final ConcurrentMap<String, HarnessAgentRuntime> active = new ConcurrentHashMap<>();

    /** 保护本地执行目录准备与提交的锁。 */
    private final FileChannel directoryLock;

    /** 保护目录所有权相关共享状态的互斥控制对象。 */
    private final FileLock directoryOwnership;

    /**
     * 创建已发布Agent运行时，初始化该组件所需的状态、配置或依赖。
     *
     * @param releases 提供发布集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param controls 当前已发布Agent运行时持有的控制集合对象，供相应处理步骤使用。
     * @param executionRoot 当前已发布Agent运行时持有的执行根对象，供相应处理步骤使用。
     * @param concurrency 当前已发布Agent运行时使用的并发，供其处理与状态记录使用。
     * @param factory 当前已发布Agent运行时持有的工厂对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public PublishedAgentRuntime(
            AgentReleaseService releases,
            AgentRuntime controls,
            Path executionRoot,
            int concurrency,
            BiFunction<AgentReleaseSnapshot, Path, HarnessAgentRuntime> factory) {
        this.releases = releases;
        this.controls = controls;
        this.executionRoot = executionRoot;
        this.factory = factory;
        this.capacity = new Semaphore(concurrency);
        FileChannel channel = null;
        try {
            Files.createDirectories(executionRoot);
            channel =
                    FileChannel.open(
                            executionRoot.resolve(".instance.lock"),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
            var ownership = channel.tryLock();
            if (ownership == null)
                throw new IllegalStateException(
                        "Publication execution directory is already used by another instance");
            try (var paths = Files.list(executionRoot)) {
                for (Path old :
                        paths.filter(p -> p.getFileName().toString().startsWith("execution-"))
                                .toList()) cleanup(null, null, old);
            }
            directoryLock = channel;
            directoryOwnership = ownership;
        } catch (Exception error) {
            try {
                if (channel != null) channel.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw new IllegalStateException(
                    "Cannot acquire private publication execution directory", error);
        }
    }

    /**
     * 产生执行流并返回已发布Agent运行时。
     *
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws SessionTurnBusyException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Flux<AgentRuntimeEvent> stream(AgentTurnRequest request) {
        return Flux.defer(
                () -> {
                    if (!capacity.tryAcquire())
                        return Flux.error(
                                new IllegalStateException(
                                        "Published workspace execution capacity exhausted"));
                    AgentReleaseSnapshot snapshot = null;
                    Path dir = null;
                    HarnessAgentRuntime runtime = null;
                    try {
                        snapshot =
                                releases.beginExecution(
                                        request.getOwnerKey(),
                                        request.getSessionId(),
                                        !request.getApprovalDecisions().isEmpty()
                                                || !request.getAskUserDecisions().isEmpty());
                        Files.createDirectories(executionRoot);
                        dir = Files.createTempDirectory(executionRoot, "execution-");
                        for (var a : snapshot.getManifest().getAssets()) {
                            Path target = dir.resolve(a.getPath());
                            Files.createDirectories(target.getParent());
                            try (var input = snapshot.open(a.getPath())) {
                                Files.copy(input, target);
                            }
                        }
                        runtime = factory.apply(snapshot, dir);
                        String slot = request.getOwnerKey() + "\0" + request.getSessionId();
                        if (active.putIfAbsent(slot, runtime) != null)
                            throw new SessionTurnBusyException();
                        AgentReleaseSnapshot pinned = snapshot;
                        Path workspace = dir;
                        HarnessAgentRuntime running = runtime;
                        List<AgentContextBinding<?>> bindings =
                                new ArrayList<>(request.getContextBindings());
                        bindings.removeIf(
                                b ->
                                        b.getType() == SkillReleaseContext.class
                                                || b.getType() == AgentReleaseSnapshot.class);
                        bindings.add(new AgentContextBinding<>(AgentReleaseSnapshot.class, pinned));
                        bindings.add(
                                new AgentContextBinding<>(
                                        SkillReleaseContext.class,
                                        new SkillReleaseContext(pinned.getSkills())));
                        for (int i = 0; i < bindings.size(); i++)
                            if (bindings.get(i).getValue() instanceof HorizenTraceContext trace) {
                                Map<String, Object> metadata =
                                        new LinkedHashMap<>(trace.getMetadata());
                                metadata.put(
                                        "agentReleaseHash", pinned.getManifest().getReleaseHash());
                                metadata.put("agentReleaseNo", pinned.getManifest().getReleaseNo());
                                bindings.set(
                                        i,
                                        new AgentContextBinding<>(
                                                HorizenTraceContext.class,
                                                new HorizenTraceContext(
                                                        trace.getTurnId(),
                                                        trace.getTraceId(),
                                                        trace.getSessionId(),
                                                        trace.getUserId(),
                                                        trace.getName(),
                                                        metadata)));
                            }
                        AgentTurnRequest call =
                                new AgentTurnRequest(
                                        request.getTurnId(),
                                        request.getOwnerKey(),
                                        request.getSessionId(),
                                        request.getMessage(),
                                        request.getApprovalDecisions(),
                                        request.getAskUserDecisions(),
                                        request.getAttachments(),
                                        bindings);
                        return Flux.using(
                                () -> pinned,
                                bound -> running.stream(call),
                                bound -> {
                                    active.remove(slot, running);
                                    try {
                                        cleanup(running, bound, workspace);
                                    } finally {
                                        capacity.release();
                                    }
                                },
                                true);
                    } catch (Exception error) {
                        if (runtime != null)
                            active.remove(
                                    request.getOwnerKey() + "\0" + request.getSessionId(), runtime);
                        cleanup(runtime, snapshot, dir);
                        capacity.release();
                        return Flux.error(error);
                    }
                });
    }

    /**
     * 完成当前操作的cleanup步骤，按实现更新相应状态或依赖。
     *
     * @param runtime 执行 Agent 模型与工具循环的运行时接口。
     * @param snapshot 当前已发布Agent运行时持有的快照对象，供相应处理步骤使用。
     * @param root 当前操作允许使用的根路径。
     */
    private static void cleanup(AgentRuntime runtime, AgentReleaseSnapshot snapshot, Path root) {
        try {
            if (runtime != null) runtime.close();
        } catch (Exception error) {
            LOG.warn("Publication runtime cleanup failed ({})", error.getClass().getSimpleName());
        } finally {
            if (snapshot != null) snapshot.close();
            try {
                if (root != null && Files.exists(root))
                    try (var paths = Files.walk(root)) {
                        for (Path p : paths.sorted(Comparator.reverseOrder()).toList())
                            Files.deleteIfExists(p);
                    }
            } catch (IOException error) {
                LOG.warn(
                        "Publication execution directory cleanup failed ({})",
                        error.getClass().getSimpleName());
            }
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前PublishedAgentRuntime处理步骤使用。
     *
     * @param o 参与当前转换或比较的对象。
     * @param s 待处理文本。
     * @return 本次操作返回的Agent运行时结果。
     */
    private AgentRuntime control(String o, String s) {
        AgentRuntime runtime = active.get(o + "\0" + s);
        return runtime == null ? controls : runtime;
    }

    /**
     * 计算或取得本方法声明的结果，供当前PublishedAgentRuntime处理步骤使用。
     *
     * @param o 参与当前转换或比较的对象。
     * @param s 待处理文本。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<SessionExecutionState> sessionExecution(String o, String s) {
        return control(o, s).sessionExecution(o, s);
    }

    /**
     * 检查timeoutCurrentTurn对应的条件，供调用方选择后续处理分支。
     *
     * @param o 参与当前转换或比较的对象。
     * @param s 待处理文本。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean timeoutCurrentTurn(String o, String s) {
        return control(o, s).timeoutCurrentTurn(o, s);
    }

    /**
     * 检查timeoutCurrentTurn对应的条件，供调用方选择后续处理分支。
     *
     * @param o 参与当前转换或比较的对象。
     * @param s 待处理文本。
     * @param t 当前已发布Agent运行时使用的类型参数，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean timeoutCurrentTurn(String o, String s, String t) {
        return control(o, s).timeoutCurrentTurn(o, s, t);
    }

    /**
     * 检查interruptCurrentTurn对应的条件，供调用方选择后续处理分支。
     *
     * @param o 参与当前转换或比较的对象。
     * @param s 待处理文本。
     * @param t 当前已发布Agent运行时使用的类型参数，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean interruptCurrentTurn(String o, String s, String t) {
        return control(o, s).interruptCurrentTurn(o, s, t);
    }

    /**
     * 收敛失败的当前执行。
     *
     * @param o 参与当前转换或比较的对象。
     * @param s 待处理文本。
     * @param t 当前已发布Agent运行时使用的类型参数，供其处理与状态记录使用。
     * @param f 当前已发布Agent运行时使用的f，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean failCurrentTurn(String o, String s, String t, String f) {
        return control(o, s).failCurrentTurn(o, s, t, f);
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public void close() {
        try {
            active.values().forEach(AgentRuntime::close);
            controls.close();
        } finally {
            try {
                directoryOwnership.release();
                directoryLock.close();
            } catch (IOException error) {
                LOG.warn(
                        "Publication directory ownership cleanup failed ({})",
                        error.getClass().getSimpleName());
            }
        }
    }
}
