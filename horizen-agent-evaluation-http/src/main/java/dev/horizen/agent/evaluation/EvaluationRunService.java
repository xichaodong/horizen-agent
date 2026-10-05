package dev.horizen.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.evaluation.model.EvaluationCase;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 单次执行评测用例；内存只保存执行中的状态，结果由服务器管理。
 */
public final class EvaluationRunService implements AutoCloseable {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 评测使用的执行宿主入口，负责提交输入与处理交互。 */
    private final EvaluationHost host;

    /** 按工具名维护执行元数据与可用状态的注册表。 */
    private final EvaluationSessionRegistry registry;

    /** 评测或后台工作使用的执行资源，供异步运行与关闭管理。 */
    private final ThreadPoolExecutor workers;

    /** 活跃的索引映射，供按键查找或归并当前组件的数据。 */
    private final Map<String, Run> active = new ConcurrentHashMap<>();

    /**
     * 创建评测运行服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param host 当前评测运行服务持有的宿主对象，供相应处理步骤使用。
     * @param registry 当前评测运行服务持有的注册表对象，供相应处理步骤使用。
     * @param concurrency 当前评测运行服务使用的并发，供其处理与状态记录使用。
     */
    public EvaluationRunService(
            EvaluationHost host, EvaluationSessionRegistry registry, int concurrency) {
        this.host = host;
        this.registry = registry;
        workers =
                new ThreadPoolExecutor(
                        concurrency,
                        concurrency,
                        0,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        r -> {
                            Thread t = new Thread(r, "agent-evaluation");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /**
     * 检查terminal对应的条件，供调用方选择后续处理分支。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public static boolean terminal(String status) {
        return List.of(
                        "COMPLETED",
                        "INTERACTION_REQUIRED",
                        "AGENT_FAILED",
                        "ERROR",
                        "CANCELLED",
                        "TIMED_OUT")
                .contains(status);
    }

    /**
     * 启动评测运行服务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized CompletableFuture<EvaluationProtocol.Status> start(
            ExecutionIdentity identity, EvaluationProtocol.Start request) {
        var caseInput = EvaluationCaseParser.parse(request);
        String key = key(identity.getOwnerKey(), request.getExecutionId());
        if (JSON.valueToTree(request).toString().length() > 16 * 1024 * 1024)
            throw new IllegalArgumentException("Evaluation input exceeds limit");
        if (active.containsKey(key))
            throw new IllegalArgumentException("Execution is already running");
        EvaluationFixture fixture =
                new EvaluationFixture(request.getFixture(), caseInput.getFaults());
        fixture.liveLimits(caseInput.getMaxLiveCalls());
        EvaluationProtocol.Status result = new EvaluationProtocol.Status();
        result.setExecutionId(request.getExecutionId());
        result.setCaseRunId(request.getCaseRunId());
        result.setSessionId("eval-" + key.substring(0, 32));
        result.setStatus("RUNNING");
        result.setStartedAtMs(System.currentTimeMillis());
        Run run = new Run(identity, request, result, fixture, caseInput);
        active.put(key, run);
        try {
            workers.execute(() -> execute(key, run, identity));
        } catch (RejectedExecutionException error) {
            active.remove(key);
            throw error;
        }
        return run.completion;
    }

    /**
     * 计算或取得本方法声明的结果，供当前EvaluationRunService处理步骤使用。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param id 目标对象的标识。
     * @return 本次操作返回的状态结果。
     * @throws NoSuchElementException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public EvaluationProtocol.Status status(ExecutionIdentity identity, String id) {
        Run run = active.get(key(identity.getOwnerKey(), id));
        if (run == null) throw new NoSuchElementException("Evaluation execution is not running");
        synchronized (run) {
            return copy(run.result);
        }
    }

    /**
     * 取消评测运行服务。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param id 目标对象的标识。
     * @return 本次操作返回的状态结果。
     * @throws NoSuchElementException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public EvaluationProtocol.Status cancel(ExecutionIdentity identity, String id) {
        Run run = active.get(key(identity.getOwnerKey(), id));
        if (run == null) throw new NoSuchElementException("Evaluation execution is not running");
        run.cancelled.set(true);
        try {
            host.cancel(identity, run.result.getSessionId());
        } finally {
            Thread thread = run.thread;
            if (thread != null) thread.interrupt();
        }
        synchronized (run) {
            return copy(run.result);
        }
    }

    /**
     * 执行评测运行服务。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param key 当前对象的查找或写入键。
     * @param run 当前评测运行服务持有的运行对象，供相应处理步骤使用。
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void execute(String key, Run run, ExecutionIdentity identity) {
        run.thread = Thread.currentThread();
        EvaluationProtocol.Start request = run.request;
        EvaluationProtocol.Status result = run.result;
        EvaluationEvidence evidence = new EvaluationEvidence(result);
        evidence.externalModels();
        run.fixture.setModelObserver(
                payload -> {
                    synchronized (run) {
                        evidence.add("LLM_CALL", "model.call", "AGENT", payload);
                    }
                });
        long deadline = result.getStartedAtMs() + request.getTimeoutMs();
        try {
            registry.bind(identity.getOwnerKey(), result.getSessionId(), run.fixture);
            var interactions = run.caseInput.getInteractions();
            int interactionIndex = 0, stepIndex = 0;
            var steps = run.caseInput.getSteps();
            for (EvaluationProtocol.Step step : steps) {
                check(run, deadline);
                synchronized (run) {
                    evidence.add(
                            "MESSAGE",
                            "user.message",
                            "USER",
                            Map.of("content", step.getUserInput()));
                }
                AtomicReference<AgentRuntimeEvent> last = new AtomicReference<>();
                Consumer<AgentRuntimeEvent> consumer =
                        e -> {
                            synchronized (run) {
                                evidence.accept(e);
                                last.set(e);
                                if (e.getType() == AgentRuntimeEvent.Type.TOOL_COMPLETED
                                        && run.fixture.getFailure() != null)
                                    throw new IllegalStateException(run.fixture.getFailure());
                            }
                        };
                host.start(
                        identity,
                        result.getSessionId(),
                        request.getExecutionId() + "-step-" + (stepIndex++),
                        step,
                        remaining(deadline),
                        consumer);
                while (last.get() != null && pending(last.get())) {
                    check(run, deadline);
                    if (run.fixture.getFailure() != null)
                        throw new IllegalStateException(run.fixture.getFailure());
                    run.pending = last.get();
                    run.unexecutedSteps = steps.size() - stepIndex;
                    while (interactionIndex < interactions.size()
                            && interactions.get(interactionIndex).isOptional()
                            && last.get().getType() != AgentRuntimeEvent.Type.ASK_USER_REQUIRED) {
                        skippedOptional(run, evidence, interactionIndex++);
                    }
                    if (interactionIndex >= interactions.size()) {
                        interactionRequired(run, evidence);
                        try {
                            host.cancel(identity, result.getSessionId());
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                    EvaluationProtocol.Interaction interaction =
                            interactions.get(interactionIndex++);
                    if (interaction.getDelayMs() > 0)
                        Thread.sleep(
                                Math.min(interaction.getDelayMs(), remaining(deadline).toMillis()));
                    check(run, deadline);
                    AgentRuntimeEvent suspended = last.get();
                    last.set(null);
                    host.resume(
                            identity,
                            result.getSessionId(),
                            suspended,
                            interaction,
                            remaining(deadline),
                            consumer);
                    run.pending = null;
                }
                check(run, deadline);
                if (last.get() != null
                        && last.get().getType() == AgentRuntimeEvent.Type.TURN_FAILED) {
                    agentFailed(run, evidence, last.get());
                    return;
                }
                if (last.get() == null
                        || last.get().getType() != AgentRuntimeEvent.Type.TURN_COMPLETED)
                    throw new IllegalStateException("AGENT_EXECUTION_FAILED");
            }
            while (interactionIndex < interactions.size()
                    && interactions.get(interactionIndex).isOptional())
                skippedOptional(run, evidence, interactionIndex++);
            if (interactionIndex != interactions.size())
                throw new IllegalStateException("UNUSED_INTERACTION_SCRIPT");
            if (run.fixture.getFailure() != null)
                throw new IllegalStateException(run.fixture.getFailure());
            synchronized (run) {
                result.setStatus("COMPLETED");
                result.setFixtureRecord(run.fixture.recorded());
            }
        } catch (Exception error) {
            if (!run.cancelled.get()
                    && System.currentTimeMillis() < deadline
                    && run.fixture.getFailure() == null
                    && run.pending != null
                    && error instanceof IllegalStateException
                    && List.of(
                                    "ASK_USER_SCRIPT_MISMATCH",
                                    "APPROVAL_SCRIPT_TOOL_MISMATCH",
                                    "INTERACTION_SCRIPT_TYPE_MISMATCH")
                            .contains(error.getMessage())) {
                interactionRequired(run, evidence);
                try {
                    host.cancel(identity, result.getSessionId());
                } catch (Exception ignored) {
                }
                return;
            }
            Logger.getLogger(EvaluationRunService.class.getName())
                    .warning(
                            "Evaluation failure category="
                                    + error.getClass().getSimpleName()
                                    + " frames="
                                    + Arrays.stream(error.getStackTrace()).limit(20).toList());
            try {
                host.cancel(identity, result.getSessionId());
            } catch (Exception ignored) {
            }
            synchronized (run) {
                result.setStatus(
                        run.cancelled.get()
                                ? "CANCELLED"
                                : System.currentTimeMillis() >= deadline ? "TIMED_OUT" : "ERROR");
                result.setErrorCode(
                        run.fixture.getFailure() != null
                                ? run.fixture.getFailure()
                                : "COMPLETED".equals(result.getStatus())
                                        ? null
                                        : result.getStatus());
                // 仅保留安全的错误分类，任意模型或 Provider 异常消息可能包含秘密。
                result.setErrorMessage(
                        error instanceof IllegalStateException
                                ? error.getMessage()
                                : error.getClass().getSimpleName());
                if (!run.fixture.replayMisses().isEmpty()) {
                    try {
                        evidence.add(
                                "STATE_CHANGE",
                                "fixture.miss",
                                "SYSTEM",
                                Map.of("requests", run.fixture.replayMisses()));
                    } catch (IllegalStateException limit) {
                        /** 证据容量已满时，仍保留原始失败原因。 */
                    }
                }
            }
        } finally {
            synchronized (run) {
                result.setFixtureRecord(run.fixture.recorded());
                evidence.seal();
                result.setFinishedAtMs(System.currentTimeMillis());
            }
            registry.remove(identity.getOwnerKey(), result.getSessionId());
            active.remove(key);
            Thread.interrupted();
            run.completion.complete(copy(result));
        }
    }

    /**
     * 完成当前操作的interactionRequired步骤，按实现更新相应状态或依赖。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param run 当前评测运行服务持有的运行对象，供相应处理步骤使用。
     * @param evidence 当前评测运行服务持有的证据对象，供相应处理步骤使用。
     */
    private static void interactionRequired(Run run, EvaluationEvidence evidence) {
        synchronized (run) {
            AgentRuntimeEvent pending = run.pending;
            Map<String, Object> interaction = new LinkedHashMap<>();
            interaction.put(
                    "type",
                    pending.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED
                            ? "approval"
                            : "ask_user");
            interaction.put("id", pending.getId());
            interaction.put("turnId", pending.getTurnId());
            interaction.put("details", pending.getDetails());
            run.result.setStatus("INTERACTION_REQUIRED");
            run.result
                    .getActualOutput()
                    .put(
                            "evaluationExecution",
                            Map.of(
                                    "status",
                                    "INTERACTION_REQUIRED",
                                    "reason",
                                    "UNSCRIPTED_INTERACTION",
                                    "pendingInteraction",
                                    interaction,
                                    "unexecutedStepCount",
                                    run.unexecutedSteps));
            evidence.seal();
        }
    }

    /**
     * 完成当前操作的agentFailed步骤，按实现更新相应状态或依赖。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param run 当前评测运行服务持有的运行对象，供相应处理步骤使用。
     * @param evidence 当前评测运行服务持有的证据对象，供相应处理步骤使用。
     * @param failure 当前失败信息。
     */
    private static void agentFailed(
            Run run, EvaluationEvidence evidence, AgentRuntimeEvent failure) {
        synchronized (run) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("type", failure.getType().name());
            details.put("id", failure.getId());
            details.put("turnId", failure.getTurnId());
            details.put("status", failure.getStatus());
            details.put("details", failure.getDetails());
            run.result.setStatus("AGENT_FAILED");
            run.result
                    .getActualOutput()
                    .put(
                            "evaluationExecution",
                            Map.of(
                                    "status",
                                    "AGENT_FAILED",
                                    "reason",
                                    "AGENT_TURN_FAILED",
                                    "failure",
                                    details,
                                    "unexecutedStepCount",
                                    run.unexecutedSteps));
            evidence.seal();
        }
    }

    /**
     * 检查pending对应的条件，供调用方选择后续处理分支。
     *
     * @param e 当前评测运行服务持有的e对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean pending(AgentRuntimeEvent e) {
        return e.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED
                || e.getType() == AgentRuntimeEvent.Type.ASK_USER_REQUIRED;
    }

    /**
     * 完成当前操作的skippedOptional步骤，按实现更新相应状态或依赖。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param run 当前评测运行服务持有的运行对象，供相应处理步骤使用。
     * @param evidence 当前评测运行服务持有的证据对象，供相应处理步骤使用。
     * @param index 当前评测运行服务使用的索引，供其处理与状态记录使用。
     */
    private static void skippedOptional(Run run, EvaluationEvidence evidence, int index) {
        synchronized (run) {
            evidence.add(
                    "STATE_CHANGE",
                    "evaluation.optional_question_skipped",
                    "SYSTEM",
                    Map.of(
                            "scriptIndex",
                            index,
                            "type",
                            "ask_user",
                            "reason",
                            "Explicit optional question did not occur"));
        }
    }

    /**
     * 检查评测运行服务。
     *
     * @param run 当前评测运行服务持有的运行对象，供相应处理步骤使用。
     * @param deadline 当前评测运行服务使用的截止，供其处理与状态记录使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws InterruptedException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void check(Run run, long deadline) throws InterruptedException {
        if (run.cancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedException();
        if (System.currentTimeMillis() >= deadline)
            throw new IllegalStateException("EVALUATION_TIMEOUT");
    }

    /**
     * 计算或取得本方法声明的结果，供当前EvaluationRunService处理步骤使用。
     *
     * @param deadline 当前评测运行服务使用的截止，供其处理与状态记录使用。
     * @return 本次操作返回的耗时结果。
     */
    private static Duration remaining(long deadline) {
        return Duration.ofMillis(Math.max(1, deadline - System.currentTimeMillis()));
    }

    /**
     * 计算摘要评测运行服务。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(DigestUtils.newSha256().digest(JSON.writeValueAsBytes(value)));
        } catch (Exception error) {
            throw new IllegalArgumentException(error);
        }
    }

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param owner 当前评测运行服务使用的数据归属，供其处理与状态记录使用。
     * @param id 目标对象的标识。
     * @return 本次处理生成或读取的文本。
     */
    private String key(String owner, String id) {
        return hash(List.of(owner, id));
    }

    /**
     * 复制评测运行服务。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的状态结果。
     */
    private EvaluationProtocol.Status copy(EvaluationProtocol.Status value) {
        return JSON.convertValue(value, EvaluationProtocol.Status.class);
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public void close() {
        active.values()
                .forEach(
                        run -> {
                            run.cancelled.set(true);
                            try {
                                host.cancel(run.identity, run.result.getSessionId());
                            } catch (Exception ignored) {
                            }
                        });
        workers.shutdownNow();
    }

    /** 评测运行服务内部的运行，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Run {
        /** 可信宿主解析的执行身份，供访问范围与审计使用。 */
        private final ExecutionIdentity identity;

        /** 本组件使用的 {@code EvaluationProtocol.Start} 状态或依赖，用于 request 的处理。 */
        private final EvaluationProtocol.Start request;

        /** 当前执行或查询的结果，供后续状态转换或协议输出使用。 */
        private final EvaluationProtocol.Status result;

        /** 本次评测运行的固定调用样本与故障脚本。 */
        private final EvaluationFixture fixture;

        /** 当前评测用例已经规范化的输入内容。 */
        private final EvaluationCase caseInput;

        /** completion的异步完成句柄，用于等待结果或传播失败。 */
        private final CompletableFuture<EvaluationProtocol.Status> completion =
                new CompletableFuture<>();

        /** 当前执行是否已确认取消，与仅提交取消请求分开表示。 */
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** 当前后台运行使用的工作线程。 */
        private volatile Thread thread;

        /** 尚未完成处理的工作或计数，供刷新、关闭与容量控制使用。 */
        private AgentRuntimeEvent pending;

        /** 当前评测尚未执行到的步骤集合或数量。 */
        private int unexecutedSteps;
    }
}
