package dev.horizen.agent.observability.horizen;

import com.fasterxml.jackson.databind.JsonNode;

import dev.horizen.agent.context.ContextCompactionTelemetry;
import dev.horizen.agent.observability.ExecutionTraceContext;
import dev.horizen.agent.observability.TraceDataSanitizer;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/** 每次调用使用的可变累加器；仅向输出端发送不可变快照。 */
final class HorizenTraceRun {
    /** 当前组件的配置与策略参数。 */
    final HorizenTraceConfig config;

    /** 本组件写入事件或观测数据的接收端，具体协议由声明类型确定。 */
    private final HorizenTraceBatchSink sink;

    /** 按已知敏感字段名清理观测负载的脱敏器，不识别任意自由文本中的秘密。 */
    final TraceDataSanitizer sanitizer;

    /** 模型与工具调用到 Span 的关联信息，用于归并完整执行链路。 */
    private final ExecutionTraceContext correlation;

    /** 一次评测或外部协议运行的标识，用于状态与证据查询。 */
    private final String runId;

    /** 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。 */
    private final String traceId;

    /** 根Span的标识，用于关联相应记录或执行。 */
    final String rootSpanId;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。 */
    private final String userId;

    /** 当前执行 Trace 的可读名称。 */
    private final String traceName;

    /** 开始时间，单位为毫秒。 */
    private final long startedAtMs;

    /** 按采集策略允许保留的执行输入内容。 */
    private final Object traceInput;

    /** 与当前 Trace 关联的宿主元数据。 */
    private final Object traceMetadata;

    /** Span集合的索引映射，供按键查找或归并当前组件的数据。 */
    private final LinkedHashMap<String, SpanDraft> spans = new LinkedHashMap<>();

    /** 工具父级Span的标识集合，用于批量关联相应记录。 */
    final LinkedHashMap<String, String> toolParentSpanIds = new LinkedHashMap<>();

    /** 当前执行或历史事件集合，供持久化、回放与观测使用。 */
    private final ArrayList<HorizenTraceBatch.Event> events = new ArrayList<>();

    /** 当前观测或评测事件在其所属序列中的位置。 */
    private final AtomicInteger eventIndex = new AtomicInteger();

    /** 模型索引的原子状态，供并发更新与统计读取使用。 */
    final AtomicInteger modelIndex = new AtomicInteger();

    /** 已结束的原子状态，供并发更新与统计读取使用。 */
    private final AtomicBoolean ended = new AtomicBoolean();

    /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
    private volatile String status = "RUNNING";

    /** 已结束时间，单位为毫秒。 */
    private volatile Long endedAtMs;

    /** 按采集策略允许保留的执行最终输出。 */
    private volatile Object traceOutput;

    /** 本次执行最终记录的结束状态。 */
    private volatile String completionStatus = "COMPLETED";

    /**
     * 启动HorizenTrace运行。
     *
     * @param config 当前组件的配置与策略参数。
     * @param sink 当前HorizenTrace运行持有的上报端对象，供相应处理步骤使用。
     * @param sanitizer 当前HorizenTrace运行持有的清理器对象，供相应处理步骤使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param runtimeContext 当前HorizenTrace运行持有的运行时上下文对象，供相应处理步骤使用。
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次操作返回的HorizenTrace运行结果。
     */
    static HorizenTraceRun start(
            HorizenTraceConfig config,
            HorizenTraceBatchSink sink,
            TraceDataSanitizer sanitizer,
            Agent agent,
            RuntimeContext runtimeContext,
            List<Msg> messages) {
        HorizenTraceContext supplied =
                runtimeContext == null ? null : runtimeContext.get(HorizenTraceContext.class);
        var inherited =
                runtimeContext == null ? null : runtimeContext.get(ExecutionTraceContext.class);
        boolean child =
                inherited != null
                        && !Objects.equals(
                                inherited.getRuntimeSessionId(), runtimeContext.getSessionId());
        String runId =
                child
                        ? inherited.getTurnId()
                        : textOr(supplied == null ? null : supplied.getTurnId(), randomHex(16));
        String traceId =
                child
                        ? randomHex(16)
                        : textOr(supplied == null ? null : supplied.getTraceId(), randomHex(16));
        String sessionId =
                textOr(
                        supplied == null ? null : supplied.getSessionId(),
                        runtimeContext == null ? null : runtimeContext.getSessionId());
        sessionId = textOr(sessionId, runId);
        String userId =
                textOr(
                        supplied == null ? null : supplied.getUserId(),
                        runtimeContext == null ? null : runtimeContext.getUserId());
        if (child) sessionId = inherited.getSessionId();
        String name =
                child
                        ? agent.getName()
                        : textOr(
                                supplied == null ? null : supplied.getName(),
                                config.getAgentName());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", config.getSource());
        metadata.put("sdkName", config.getSdkName());
        metadata.put("sdkVersion", config.getSdkVersion());
        metadata.put("runId", runId);
        metadata.put("turnId", runId);
        metadata.put("agentName", agent.getName());
        metadata.put("configuredAgentName", config.getAgentName());
        metadata.put("executorName", config.getExecutorName());
        if (config.getEnvironment() != null) metadata.put("environment", config.getEnvironment());
        metadata.put("contentCaptured", config.isCaptureContent());
        if (supplied != null) {
            metadata.putAll(supplied.getMetadata());
        }
        // 宿主元数据不能覆盖可信执行坐标。
        metadata.put("turnId", runId);
        metadata.put("runId", runId);
        metadata.put("invocationId", randomHex(16));
        if (!child
                && runtimeContext != null
                && runtimeContext.get("agent.invocationKind") != null) {
            metadata.put("invocationKind", runtimeContext.<String>get("agent.invocationKind"));
        }
        if (child) {
            HorizenTraceParent parent = runtimeContext.get(HorizenTraceParent.class);
            metadata.put("parentTraceId", inherited.getTraceId());
            metadata.put(
                    "parentSpanId",
                    parent != null && parent.getTraceId().equals(inherited.getTraceId())
                            ? parent.getSpanId()
                            : inherited.getRootSpanId());
            metadata.put("invocationKind", "subagent");
        }
        HorizenTraceRun run =
                new HorizenTraceRun(
                        config,
                        sink,
                        sanitizer,
                        runId,
                        traceId,
                        sessionId,
                        userId,
                        name,
                        messages,
                        metadata,
                        runtimeContext == null ? sessionId : runtimeContext.getSessionId());
        if (runtimeContext != null)
            runtimeContext.put(ExecutionTraceContext.class, run.correlation);
        run.publish();
        return run;
    }

    /**
     * 创建HorizenTrace运行，初始化该组件所需的状态、配置或依赖。
     *
     * @param config 当前组件的配置与策略参数。
     * @param sink 当前HorizenTrace运行持有的上报端对象，供相应处理步骤使用。
     * @param sanitizer 当前HorizenTrace运行持有的清理器对象，供相应处理步骤使用。
     * @param runId 一次评测或外部协议运行的标识，用于状态与证据查询。
     * @param traceId 关联本次 Agent 执行的 Trace 标识，用于归并模型与工具观测。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param traceName 当前HorizenTrace运行使用的Trace名称，供其处理与状态记录使用。
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param metadata 与当前对象关联的附加元数据，不替代领域状态或授权校验。
     * @param runtimeSessionId 运行时会话的标识，用于关联相应记录或执行。
     */
    private HorizenTraceRun(
            HorizenTraceConfig config,
            HorizenTraceBatchSink sink,
            TraceDataSanitizer sanitizer,
            String runId,
            String traceId,
            String sessionId,
            String userId,
            String traceName,
            List<Msg> messages,
            Map<String, Object> metadata,
            String runtimeSessionId) {
        this.config = config;
        this.sink = sink;
        this.sanitizer = sanitizer;
        this.runId = runId;
        this.traceId = traceId;
        this.rootSpanId = randomHex(8);
        this.correlation =
                new ExecutionTraceContext(
                        traceId,
                        (String) metadata.get("invocationId"),
                        runId,
                        sessionId,
                        runtimeSessionId,
                        rootSpanId);
        this.sessionId = sessionId;
        this.userId = userId;
        this.traceName = traceName;
        this.startedAtMs = System.currentTimeMillis();
        this.traceInput = traceInput(messages);
        this.traceMetadata = sanitizer.sanitize(metadata);
        SpanDraft root =
                new SpanDraft(
                        rootSpanId,
                        null,
                        "AGENT",
                        traceName,
                        startedAtMs,
                        null,
                        null,
                        Map.of("runId", runId));
        spans.put(rootSpanId, root);
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param input 本次处理的输入。
     * @param next 将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    Flux<AgentEvent> traceModelCall(
            ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        int index = modelIndex.incrementAndGet();
        String spanId = randomHex(8);
        ModelCallCapture capture = new ModelCallCapture(this, spanId, input);
        synchronized (this) {
            spans.put(spanId, capture.span);
            addEvent(
                    spanId,
                    "STATE_CHANGE",
                    "llm.request.start",
                    "SYSTEM",
                    Map.of("modelCall", index, "model", capture.modelName));
        }
        publish();
        return next.apply(input)
                .doOnNext(capture::accept)
                .doOnComplete(capture::complete)
                .doOnError(capture::fail)
                .doOnCancel(capture::cancel);
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param model 当前HorizenTrace运行持有的模型对象，供相应处理步骤使用。
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param tools 工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param options 可供当前请求选择的选项或策略集合。
     * @param next 返回结果的工作回调，由当前操作的执行或事务边界调用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    Flux<ChatResponse> traceCompactionModelCall(
            Model model,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            Supplier<Flux<ChatResponse>> next) {
        int index = modelIndex.incrementAndGet();
        String spanId = randomHex(8);
        ModelCallInput input = new ModelCallInput(messages, tools, options, model);
        ModelCallCapture capture =
                new ModelCallCapture(this, spanId, input, "llm.compaction." + index, "compaction");
        synchronized (this) {
            spans.put(spanId, capture.span);
            addEvent(
                    spanId,
                    "STATE_CHANGE",
                    "llm.compaction.start",
                    "SYSTEM",
                    Map.of(
                            "modelCall",
                            index,
                            "model",
                            capture.modelName,
                            "operation",
                            "compaction"));
        }
        publish();
        return next.get()
                .doOnNext(capture::accept)
                .doOnComplete(capture::complete)
                .doOnError(capture::fail)
                .doOnCancel(capture::cancel);
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param input 本次处理的输入。
     * @param next 将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    Flux<AgentEvent> traceTools(ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        Map<String, ToolCallCapture> captures = new LinkedHashMap<>();
        for (ToolUseBlock call :
                input.toolCalls() == null ? List.<ToolUseBlock>of() : input.toolCalls()) {
            ToolCallCapture capture = new ToolCallCapture(this, call);
            captures.put(call.getId(), capture);
            correlation.registerTool(call.getId(), capture.span.spanId);
            synchronized (this) {
                spans.put(capture.span.spanId, capture.span);
                addEvent(
                        capture.span.spanId,
                        "TOOL_CALL",
                        call.getName(),
                        "AGENT",
                        toolCallEventPayload(call));
            }
        }
        publish();
        return next.apply(input)
                .doOnNext(
                        event -> {
                            String toolCallId = toolCallId(event);
                            ToolCallCapture capture =
                                    toolCallId == null ? null : captures.get(toolCallId);
                            if (capture != null) {
                                capture.accept(event);
                            }
                        })
                .doOnComplete(() -> captures.values().forEach(ToolCallCapture::complete))
                .doOnError(error -> captures.values().forEach(capture -> capture.fail(error)))
                .doOnCancel(() -> captures.values().forEach(ToolCallCapture::cancel));
    }

    /**
     * 观察Agent事件。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param event 当前HorizenTrace运行持有的事件对象，供相应处理步骤使用。
     */
    void observeAgentEvent(AgentEvent event) {
        // 转发的子调用事件拥有独立追踪，不能结束父调用。
        if (event.getSource() != null && !event.getSource().isBlank()) return;
        if (event instanceof CustomEvent custom
                && (ContextCompactionTelemetry.EVENT_NAME.equals(custom.getName())
                        || ContextCompactionTelemetry.FAILURE_EVENT_NAME.equals(
                                custom.getName()))) {
            synchronized (this) {
                addEvent(
                        rootSpanId,
                        "STATE_CHANGE",
                        custom.getName(),
                        "SYSTEM",
                        sanitizer.sanitize(custom.getValue()));
            }
            publish();
        } else if (event instanceof RequireUserConfirmEvent confirm) {
            synchronized (this) {
                addEvent(
                        rootSpanId,
                        "APPROVAL_REQUEST",
                        "tool.approval.required",
                        "AGENT",
                        sanitizer.sanitize(
                                Map.of(
                                        "replyId", textOr(confirm.getReplyId(), ""),
                                        "toolCalls",
                                                confirm.getToolCalls().stream()
                                                        .map(this::toolCallEventPayload)
                                                        .toList())));
            }
            publish();
        } else if (event instanceof UserConfirmResultEvent confirmed) {
            synchronized (this) {
                addEvent(
                        rootSpanId,
                        "APPROVAL_RESULT",
                        "tool.approval.resolved",
                        "USER",
                        sanitizer.sanitize(
                                Map.of(
                                        "replyId", textOr(confirmed.getReplyId(), ""),
                                        "decisions",
                                                confirmed.getConfirmResults().stream()
                                                        .map(
                                                                result ->
                                                                        Map.of(
                                                                                "toolCallId",
                                                                                        result.getToolCall()
                                                                                                .getId(),
                                                                                "toolName",
                                                                                        result.getToolCall()
                                                                                                .getName(),
                                                                                "approved",
                                                                                        result
                                                                                                .isConfirmed()))
                                                        .toList())));
            }
            publish();
        }
        if (event instanceof AgentResultEvent result && result.getResult() != null) {
            traceOutput = config.isCaptureContent() ? result.getResult().getTextContent() : null;
            GenerateReason reason = result.getResult().getGenerateReason();
            if (reason == GenerateReason.TOOL_SUSPENDED
                    || reason == GenerateReason.PERMISSION_ASKING
                    || reason == GenerateReason.MIDDLEWARE_STOP_REQUESTED) {
                completionStatus = "WAITING";
            } else if (reason == GenerateReason.INTERRUPTED) {
                completionStatus = "INTERRUPTED";
            }
            synchronized (this) {
                addEvent(rootSpanId, "MESSAGE", "agent.message", "AGENT", traceOutput);
            }
        }
    }

    /** 完成HorizenTrace运行。 */
    void complete() {
        finish(completionStatus, null);
    }

    /**
     * 收敛失败的HorizenTrace运行。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     */
    void fail(Throwable error) {
        finish("ERROR", error);
    }

    /** 取消HorizenTrace运行。 */
    void cancel() {
        finish("CANCELLED", null);
    }

    /**
     * 收敛HorizenTrace运行。
     * 并发状态更新包含比较交换操作。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param finalStatus 当前HorizenTrace运行使用的正式状态，供其处理与状态记录使用。
     * @param error 本次失败的异常，用于分类、传播或诊断。
     */
    private void finish(String finalStatus, Throwable error) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        long now = System.currentTimeMillis();
        status = finalStatus;
        endedAtMs = now;
        synchronized (this) {
            SpanDraft root = spans.get(rootSpanId);
            root.finish(
                    finalStatus.equals("COMPLETED") ? "OK" : finalStatus,
                    now,
                    null,
                    errorView(error),
                    null);
            if (error != null) {
                addEvent(rootSpanId, "ERROR", "agent.error", "SYSTEM", errorView(error));
            }
        }
        publish();
    }

    /**
     * 发布HorizenTrace运行。
     * 共享状态的关键更新在互斥区内完成。
     */
    void publish() {
        HorizenTraceBatch snapshot;
        synchronized (this) {
            snapshot = snapshot();
        }
        try {
            sink.submit(snapshot);
        } catch (RuntimeException ignored) {
            // 可观测性故障不影响执行；导出器自行提供失败计数。
        }
    }

    /**
     * 读取快照中的HorizenTrace运行。
     *
     * @return 本次操作返回的HorizenTrace批次结果。
     */
    private HorizenTraceBatch snapshot() {
        List<HorizenTraceBatch.Span> spanCopies =
                spans.values().stream().map(SpanDraft::snapshot).toList();
        HorizenTraceBatch.Trace trace =
                new HorizenTraceBatch.Trace(
                        traceId,
                        null,
                        config.getAgentTargetId(),
                        sessionId,
                        userId,
                        traceName,
                        status,
                        startedAtMs,
                        endedAtMs,
                        traceInput,
                        traceOutput,
                        traceMetadata,
                        null,
                        null);
        trace.setTurnId(runId);
        trace.setInvocationId(correlation.getInvocationId());
        trace.setParentTraceId(((JsonNode) traceMetadata).path("parentTraceId").asText(null));
        trace.setParentSpanId(((JsonNode) traceMetadata).path("parentSpanId").asText(null));
        trace.setInvocationKind(((JsonNode) traceMetadata).path("invocationKind").asText(null));
        return new HorizenTraceBatch(
                1,
                config.getProjectId(),
                config.getSource(),
                config.getSdkName(),
                config.getSdkVersion(),
                trace,
                spanCopies,
                List.copyOf(events));
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     *
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次操作返回的对象结果。
     */
    private Object messageView(List<Msg> messages) {
        List<Msg> safe = messages == null ? List.of() : messages;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Msg message : safe) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("role", message.getRole() == null ? null : message.getRole().name());
            if (config.isCaptureContent()) {
                value.put("content", sanitizer.sanitize(message.getContent()));
            } else {
                value.put(
                        "blockTypes",
                        message.getContent().stream()
                                .map(block -> block.getClass().getSimpleName())
                                .toList());
                value.put("textLength", message.getTextContent().length());
            }
            result.add(value);
        }
        return Map.of("messageCount", result.size(), "messages", result);
    }

    /** 提取最新用户文本作为追踪级输入；内容采集关闭或没有用户消息时返回空值。 */
    private Object traceInput(List<Msg> messages) {
        if (!config.isCaptureContent() || messages == null) {
            return null;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (message != null && message.getRole() == MsgRole.USER) {
                return message.getTextContent();
            }
        }
        return null;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     *
     * @param input 本次处理的输入。
     * @return 本次操作返回的对象结果。
     */
    Object modelInput(ModelCallInput input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("messages", messageView(input.messages()));
        value.put("tools", toolSchemaView(input.tools()));
        value.put("options", safeOptions(input.options()));
        return value;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     *
     * @param tools 工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次操作返回的对象结果。
     */
    private Object toolSchemaView(List<ToolSchema> tools) {
        return tools.stream()
                .map(
                        tool -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("name", tool.getName());
                            if (config.isCaptureContent()) {
                                value.put("description", tool.getDescription());
                                value.put("parameters", sanitizer.sanitize(tool.getParameters()));
                            }
                            return value;
                        })
                .toList();
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     *
     * @param options 可供当前请求选择的选项或策略集合。
     * @return 本次操作返回的对象结果。
     */
    private static Object safeOptions(GenerateOptions options) {
        if (options == null) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        put(safe, "modelName", options.getModelName());
        put(safe, "temperature", options.getTemperature());
        put(safe, "topP", options.getTopP());
        put(safe, "maxTokens", options.getMaxTokens());
        put(safe, "maxCompletionTokens", options.getMaxCompletionTokens());
        put(safe, "reasoningEffort", options.getReasoningEffort());
        put(safe, "thinkingBudget", options.getThinkingBudget());
        put(safe, "parallelToolCalls", options.getParallelToolCalls());
        put(safe, "toolChoice", options.getToolChoice());
        return safe;
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     *
     * @param call 当前HorizenTrace运行持有的调用对象，供相应处理步骤使用。
     * @return 本次操作返回的对象结果。
     */
    Object toolCallInput(ToolUseBlock call) {
        if (config.isCaptureContent()) {
            return sanitizer.sanitize(call.getInput());
        }
        return Map.of(
                "contentCaptured",
                false,
                "argumentNames",
                call.getInput() == null ? List.of() : call.getInput().keySet());
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     *
     * @param call 当前HorizenTrace运行持有的调用对象，供相应处理步骤使用。
     * @return 本次操作返回的对象结果。
     */
    private Object toolCallEventPayload(ToolUseBlock call) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", call.getId());
        payload.put("toolName", call.getName());
        payload.put("arguments", toolCallInput(call));
        return payload;
    }

    /**
     * 增加事件。
     *
     * @param spanId 当前观测操作的 Span 标识，供追踪父子操作关系。
     * @param eventType 当前HorizenTrace运行使用的事件类型，供其处理与状态记录使用。
     * @param name 需要定位或处理的名称。
     * @param role 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
     * @param payload 当前HorizenTrace运行持有的负载对象，供相应处理步骤使用。
     */
    synchronized void addEvent(
            String spanId, String eventType, String name, String role, Object payload) {
        events.add(
                new HorizenTraceBatch.Event(
                        randomHex(12),
                        spanId,
                        eventIndex.incrementAndGet(),
                        eventType,
                        name,
                        role,
                        System.currentTimeMillis(),
                        payload));
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次操作返回的对象结果。
     */
    Object errorView(Throwable error) {
        if (error == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", error.getClass().getName());
        if (config.isCaptureContent() && error.getMessage() != null) {
            value.put("message", error.getMessage());
        }
        return sanitizer.sanitize(value);
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenTraceRun处理步骤使用。
     *
     * @param usage 当前HorizenTrace运行持有的用量对象，供相应处理步骤使用。
     * @return 按返回类型约定组织的结果映射。
     */
    static Map<String, Object> usage(ChatUsage usage) {
        if (usage == null) {
            return null;
        }
        return Map.of(
                "inputTokens",
                usage.getInputTokens(),
                "outputTokens",
                usage.getOutputTokens(),
                "totalTokens",
                usage.getTotalTokens(),
                "cacheReadInputTokens",
                usage.getCachedTokens(),
                "timeSeconds",
                usage.getTime());
    }

    /**
     * 生成当前操作所需的toolCallId文本，供调用方继续处理。
     *
     * @param event 当前HorizenTrace运行持有的事件对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String toolCallId(AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent value) return value.getToolCallId();
        if (event instanceof ToolResultDataDeltaEvent value) return value.getToolCallId();
        if (event instanceof ToolResultEndEvent value) return value.getToolCallId();
        return null;
    }

    /**
     * 写入HorizenTrace运行。
     *
     * @param map 映射的索引映射，供按键查找或归并当前组件的数据。
     * @param key 当前对象的查找或写入键。
     * @param value 待校验、转换或保存的原始值。
     */
    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    /**
     * 生成当前操作所需的textOr文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param fallback 当前HorizenTrace运行使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 生成当前操作所需的randomHex文本，供调用方继续处理。
     *
     * @param bytes 当前操作处理的内容字节。
     * @return 本次处理生成或读取的文本。
     */
    static String randomHex(int bytes) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, bytes * 2);
    }
}
