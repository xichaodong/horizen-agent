package dev.horizen.agent.application.turn;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import lombok.AccessLevel;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按执行隔离、容量受限的工具事实组装器；原始增量仅保存在 Redis 日志中。 */
public final class ToolTimelineProjection {
    /** 最大输入字节的固定取值，用于相应策略和边界判断。 */
    private static final int MAX_INPUT_BYTES = 256 * 1024;

    /** 最大输出字节的固定取值，用于相应策略和边界判断。 */
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    /** 最大活跃调用集合的固定取值，用于相应策略和边界判断。 */
    private static final int MAX_ACTIVE_CALLS = 128;

    /** 最大总计字节的固定取值，用于相应策略和边界判断。 */
    private static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;

    /** 调用集合的索引映射，供按键查找或归并当前组件的数据。 */
    private final Map<String, Buffer> calls = new LinkedHashMap<>();

    /** 本组件使用的 {@code AgentTurnRequest} 状态或依赖，用于 request 的处理。 */
    private final AgentTurnRequest request;

    /** 保留单次工具输入历史的字符数上限。 */
    private final int inputLimit;

    /** 保留单次工具输出历史的字符数上限。 */
    private final int outputLimit;

    /** 本次工具历史投影允许保留的总容量上限。 */
    private final int totalLimit;

    /** 当前内容字节或字节计数，用于传输、校验与容量控制。 */
    @Getter(AccessLevel.PACKAGE)
    private int bytes;

    /**
     * 创建工具时间线投影，初始化该组件所需的状态、配置或依赖。
     *
     * @param request 当前操作的请求参数。
     */
    public ToolTimelineProjection(AgentTurnRequest request) {
        this(request, MAX_INPUT_BYTES, MAX_OUTPUT_BYTES, MAX_TOTAL_BYTES);
    }

    /**
     * 创建工具时间线投影，初始化该组件所需的状态、配置或依赖。
     *
     * @param request 当前操作的请求参数。
     * @param inputLimit 当前工具时间线投影使用的输入上限，供其处理与状态记录使用。
     * @param outputLimit 当前工具时间线投影使用的输出上限，供其处理与状态记录使用。
     * @param totalLimit 当前工具时间线投影使用的总计上限，供其处理与状态记录使用。
     */
    public ToolTimelineProjection(
            AgentTurnRequest request, int inputLimit, int outputLimit, int totalLimit) {
        this.request = request;
        this.inputLimit = inputLimit;
        this.outputLimit = outputLimit;
        this.totalLimit = totalLimit;
    }

    /**
     * 接收并处理工具时间线投影。
     *
     * @param event 当前工具时间线投影持有的事件对象，供相应处理步骤使用。
     */
    public synchronized void accept(AgentRuntimeEvent event) {
        if (event.getType() == AgentRuntimeEvent.Type.TOOL_STARTED) {
            buffer(event);
        } else if (event.getType() == AgentRuntimeEvent.Type.TOOL_INPUT_DELTA
                || event.getType() == AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA) {
            Buffer buffer = buffer(event);
            boolean input = event.getType() == AgentRuntimeEvent.Type.TOOL_INPUT_DELTA;
            if (input && buffer.seeded) {
                bytes -= buffer.inputBytes;
                buffer.inputBytes = 0;
                buffer.input.setLength(0);
                buffer.seeded = false;
            }
            String value =
                    event.getDetails() == null
                            ? ""
                            : event.getDetails() instanceof String text
                                    ? text
                                    : JsonUtils.toJson(event.getDetails());
            append(buffer, input, value);
        }
    }

    /**
     * 完成工具时间线投影。
     *
     * @param event 当前工具时间线投影持有的事件对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent运行时事件结果。
     */
    public synchronized AgentRuntimeEvent complete(AgentRuntimeEvent event) {
        Buffer buffer = buffer(event);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", 1);
        // 恢复 ask_user 时可能直接产生结果而不重新生成原始输入。缺少输入时应省略该字段，
        // 保留之前的持久化快照。
        if (buffer.hasInput) snapshot.put("input", buffer.input.toString());
        snapshot.put("output", buffer.output.toString());
        calls.remove(key(event));
        bytes -= buffer.inputBytes + buffer.outputBytes;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("toolCallSnapshot", snapshot);
        if (event.getDetails() != null) details.put("metadata", event.getDetails());
        return AgentRuntimeEvent.builder()
                .type(event.getType())
                .turnId(event.getTurnId())
                .sessionId(event.getSessionId())
                .id(event.getId())
                .title(event.getTitle())
                .text(event.getText())
                .status(event.getStatus())
                .toolName(event.getToolName())
                .details(details)
                .durationMs(event.getDurationMs())
                .latencyMs(event.getLatencyMs())
                .source(event.getSource())
                .taskId(event.getTaskId())
                .parentSessionId(event.getParentSessionId())
                .agentId(event.getAgentId())
                .depth(event.getDepth())
                .streamSequence(event.getStreamSequence())
                .build();
    }

    /** 清理工具时间线投影。 */
    public synchronized void clear() {
        calls.clear();
        bytes = 0;
    }

    /**
     * 计算或取得本方法声明的结果，供当前ToolTimelineProjection处理步骤使用。
     *
     * @return 本次操作返回的整数结果。
     */
    int activeCalls() {
        return calls.size();
    }

    /**
     * 计算或取得本方法声明的结果，供当前ToolTimelineProjection处理步骤使用。
     *
     * @param event 当前工具时间线投影持有的事件对象，供相应处理步骤使用。
     * @return 本次操作返回的缓冲结果。
     * @throws SnapshotLimitException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private Buffer buffer(AgentRuntimeEvent event) {
        String key = key(event);
        Buffer existing = calls.get(key);
        if (existing != null) return existing;
        if (calls.size() >= MAX_ACTIVE_CALLS) throw new SnapshotLimitException();
        Buffer created = new Buffer();
        if (event.getSource() == null || event.getSource().isBlank()) {
            request.getApprovalDecisions().stream()
                    .filter(value -> value.getToolCallId().equals(event.getId()))
                    .findFirst()
                    .ifPresent(
                            value -> {
                                append(created, true, JsonUtils.toJson(value.getInput()));
                                created.seeded = true;
                            });
        }
        calls.put(key, created);
        return created;
    }

    /**
     * 追加工具时间线投影。
     *
     * @param buffer 当前工具时间线投影持有的缓冲对象，供相应处理步骤使用。
     * @param input 本次处理的输入。
     * @param value 待校验、转换或保存的原始值。
     * @throws SnapshotLimitException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void append(Buffer buffer, boolean input, String value) {
        int added = value.getBytes(StandardCharsets.UTF_8).length;
        int fieldBytes = input ? buffer.inputBytes : buffer.outputBytes;
        if (added > (input ? inputLimit : outputLimit) - fieldBytes || added > totalLimit - bytes) {
            throw new SnapshotLimitException();
        }
        if (input) {
            buffer.input.append(value);
            buffer.inputBytes += added;
            buffer.hasInput = true;
        } else {
            buffer.output.append(value);
            buffer.outputBytes += added;
        }
        bytes += added;
    }

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param event 当前工具时间线投影持有的事件对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String key(AgentRuntimeEvent event) {
        return (event.getSource() == null ? "" : event.getSource())
                + '\0'
                + (event.getTaskId() == null ? "" : event.getTaskId())
                + '\0'
                + event.getId();
    }

    /** 工具时间线投影内部的缓冲，封装该步骤需要的状态或输入输出。 */
    private static final class Buffer {
        /** 当前操作的输入数据，格式由所属命令、协议或工具定义。 */
        private final StringBuilder input = new StringBuilder();

        /** 当前操作产生的输出数据，供结果转换与交付使用。 */
        private final StringBuilder output = new StringBuilder();

        /** 输入的字节数，用于容量或传输限制。 */
        private int inputBytes;

        /** 输出的字节数，用于容量或传输限制。 */
        private int outputBytes;

        /** 是否存在输入的状态标记，用于选择当前组件的处理路径。 */
        private boolean hasInput;

        /** seeded的状态标记，用于选择当前组件的处理路径。 */
        private boolean seeded;
    }

    /** 快照上限异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。 */
    public static final class SnapshotLimitException extends IllegalStateException {
        /** 创建快照上限异常，初始化该组件所需的状态、配置或依赖。 */
        SnapshotLimitException() {
            super("TOOL_SNAPSHOT_LIMIT: tool history buffer capacity exceeded");
        }
    }
}
