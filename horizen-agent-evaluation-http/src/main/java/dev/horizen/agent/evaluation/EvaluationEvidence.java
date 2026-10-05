package dev.horizen.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从完整事实生成旧版用例执行证据，不使用不完整的流式 JSON。 */
@RequiredArgsConstructor
public final class EvaluationEvidence {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 当前执行或查询的结果，供后续状态转换或协议输出使用。 */
    private final EvaluationProtocol.Status result;

    /** 工具集合的索引映射，供按键查找或归并当前组件的数据。 */
    private final Map<String, Buffer> tools = new LinkedHashMap<>();

    /** 当前内容字节或字节计数，用于传输、校验与容量控制。 */
    private int bytes;

    /** 缓冲的字节数，用于容量或传输限制。 */
    private int bufferedBytes;

    /** externalModels的状态标记，用于选择当前组件的处理路径。 */
    private boolean externalModels;

    /** sealed的状态标记，用于选择当前组件的处理路径。 */
    private boolean sealed;

    /** 完成当前操作的seal步骤，按实现更新相应状态或依赖。 */
    public synchronized void seal() {
        if (sealed) return;
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put(
                "toolCalls",
                result.getEvents().stream()
                        .filter(e -> "TOOL_CALL".equals(e.getEventType()))
                        .count());
        counters.put(
                "askUser",
                result.getEvents().stream()
                        .filter(e -> "ASK_USER_REQUIRED".equals(e.getEventName()))
                        .count());
        counters.put(
                "uiInteractions",
                result.getEvents().stream()
                        .filter(
                                e ->
                                        List.of("ASK_USER_REQUIRED", "APPROVAL_REQUIRED")
                                                .contains(e.getEventName()))
                        .count());
        counters.put(
                "successCount",
                result.getEvents().stream()
                        .filter(
                                e ->
                                        "TOOL_RESULT".equals(e.getEventType())
                                                && e.getPayload() instanceof Map<?, ?> p
                                                && "success".equals(p.get("status")))
                        .count());
        counters.put(
                "failCount",
                result.getEvents().stream()
                        .filter(
                                e ->
                                        "TOOL_RESULT".equals(e.getEventType())
                                                && e.getPayload() instanceof Map<?, ?> p
                                                && "error".equals(p.get("status")))
                        .count());
        result.getActualOutput().put("counters", counters);
        sealed = true;
    }

    /** 读取externalModels的当前值。 */
    public void externalModels() {
        externalModels = true;
    }

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param e 当前评测证据持有的e对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private String key(AgentRuntimeEvent e) {
        return e.getTurnId() + ":" + e.getSource() + ":" + e.getTaskId() + ":" + e.getId();
    }

    /**
     * 解析评测证据。
     *
     * @param text 面向消息或事件消费者的文本内容。
     * @return 本次操作返回的对象结果。
     */
    private static Object parse(String text) {
        try {
            return JSON.readValue(text, Object.class);
        } catch (Exception ignored) {
            return text;
        }
    }

    /**
     * 增加评测证据。
     *
     * @param type 当前操作使用的目标类型或类别。
     * @param name 需要定位或处理的名称。
     * @param role 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
     * @param payload 当前评测证据持有的负载对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized void add(String type, String name, String role, Object payload) {
        if (sealed) return;
        try {
            bytes += JSON.writeValueAsBytes(payload).length;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        if (bytes > 16 * 1024 * 1024 || result.getEvents().size() >= 10000)
            throw new IllegalStateException("EVIDENCE_LIMIT");
        EvaluationProtocol.Event event = new EvaluationProtocol.Event();
        event.setEventIndex(result.getEvents().size() + 1);
        event.setEventType(type);
        event.setEventName(name);
        event.setRole(role);
        event.setTimestampMs(System.currentTimeMillis());
        event.setPayload(payload);
        result.getEvents().add(event);
    }

    /**
     * 接收并处理评测证据。
     *
     * @param e 当前评测证据持有的e对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized void accept(AgentRuntimeEvent e) {
        if (sealed) return;
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("turnId", e.getTurnId());
        provenance.put("toolCallId", e.getId());
        provenance.put("source", e.getSource());
        provenance.put("taskId", e.getTaskId());
        if (tools.size() > 128) throw new IllegalStateException("EVIDENCE_LIMIT");
        if (e.getType() == AgentRuntimeEvent.Type.TOOL_STARTED) {
            Buffer b = new Buffer();
            b.call = provenance;
            b.call.put("args", Map.of());
            add("TOOL_CALL", e.getToolName(), "AGENT", b.call);
            tools.put(key(e), b);
        } else if (e.getType() == AgentRuntimeEvent.Type.TOOL_INPUT_DELTA
                || e.getType() == AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA) {
            Buffer b = tools.computeIfAbsent(key(e), k -> new Buffer());
            String value;
            try {
                value =
                        e.getDetails() instanceof String s
                                ? s
                                : JSON.writeValueAsString(e.getDetails());
            } catch (Exception error) {
                throw new IllegalArgumentException(error);
            }
            StringBuilder target =
                    e.getType() == AgentRuntimeEvent.Type.TOOL_INPUT_DELTA ? b.input : b.output;
            if (target.length() + value.length() > 2 * 1024 * 1024)
                throw new IllegalStateException("EVIDENCE_LIMIT");
            int added = value.getBytes(StandardCharsets.UTF_8).length;
            bufferedBytes += added;
            b.bytes += added;
            if (bytes + bufferedBytes > 16 * 1024 * 1024)
                throw new IllegalStateException("EVIDENCE_LIMIT");
            target.append(value);
        } else if (e.getType() == AgentRuntimeEvent.Type.TOOL_COMPLETED) {
            Buffer b = tools.remove(key(e));
            if (b == null) b = new Buffer();
            bufferedBytes -= b.bytes;
            if (e.getDetails() instanceof Map<?, ?> details
                    && details.get("toolCallSnapshot") instanceof Map<?, ?> snapshot) {
                if (snapshot.get("input") != null) {
                    b.input.setLength(0);
                    b.input.append(snapshot.get("input"));
                }
                if (snapshot.get("output") != null) {
                    b.output.setLength(0);
                    b.output.append(snapshot.get("output"));
                }
            }
            if (b.call != null) {
                bytes += b.input.toString().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > 16 * 1024 * 1024) throw new IllegalStateException("EVIDENCE_LIMIT");
                b.call.put("args", b.input.isEmpty() ? Map.of() : parse(b.input.toString()));
            }
            provenance.put("result", parse(b.output.toString()));
            provenance.put("status", e.getStatus());
            add("TOOL_RESULT", e.getToolName(), "TOOL", provenance);
        } else if (e.getType() == AgentRuntimeEvent.Type.MODEL_COMPLETED && !externalModels) {
            Map<String, Object> usage = new LinkedHashMap<>();
            if (e.getDetails() instanceof Map<?, ?> raw) {
                usage.put("promptTokens", raw.get("inputTokens"));
                usage.put("completionTokens", raw.get("outputTokens"));
            }
            provenance.put("usage", usage);
            provenance.put("durationMs", e.getDurationMs());
            add("LLM_CALL", "model.call", "AGENT", provenance);
        } else if (e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED) {
            result.getActualOutput().put("lastAgentMessage", e.getText());
            add(
                    "MESSAGE",
                    "assistant.message",
                    "AGENT",
                    Map.of(
                            "content",
                            e.getText() == null ? "" : e.getText(),
                            "turnId",
                            e.getTurnId()));
        } else if (List.of(
                        AgentRuntimeEvent.Type.TURN_FAILED,
                        AgentRuntimeEvent.Type.TURN_TIMED_OUT,
                        AgentRuntimeEvent.Type.EXECUTION_NOTICE,
                        AgentRuntimeEvent.Type.CONTEXT_COMPACTION_FAILED)
                .contains(e.getType())) {
            provenance.put("details", e.getDetails());
            provenance.put("text", e.getText());
            add("ERROR", e.getType().name(), "SYSTEM", provenance);
        } else if (List.of(
                        AgentRuntimeEvent.Type.APPROVAL_REQUIRED,
                        AgentRuntimeEvent.Type.APPROVAL_RESOLVED,
                        AgentRuntimeEvent.Type.ASK_USER_REQUIRED,
                        AgentRuntimeEvent.Type.ASK_USER_RESOLVED,
                        AgentRuntimeEvent.Type.PRESENTATION_CREATED,
                        AgentRuntimeEvent.Type.CONTEXT_COMPACTED)
                .contains(e.getType())) {
            provenance.put("details", e.getDetails());
            add("STATE_CHANGE", e.getType().name(), "SYSTEM", provenance);
        }
    }

    /** 评测证据内部的缓冲，封装该步骤需要的状态或输入输出。 */
    private static final class Buffer {
        /** 当前操作的输入数据，格式由所属命令、协议或工具定义。 */
        private final StringBuilder input = new StringBuilder();

        /** 当前操作产生的输出数据，供结果转换与交付使用。 */
        private final StringBuilder output = new StringBuilder();

        /** 本次工具调用的原始调用信息。 */
        private Map<String, Object> call;

        /** 当前内容字节或字节计数，用于传输、校验与容量控制。 */
        private int bytes;
    }
}
