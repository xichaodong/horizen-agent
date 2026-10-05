package dev.horizen.agent.adapter.agentscope.askuser;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.artifact.ArtifactExecutionContext;
import dev.horizen.agent.domain.askuser.AskUserEventCollector;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.domain.askuser.AskUserStore;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 把 AgentScope 提问工具映射为可持久恢复的澄清请求。 */
public final class AskUserTool extends ToolBase {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 负责store对应持久化访问的仓储依赖；调用方通过端口隔离具体存储实现。 */
    private final AskUserStore store;

    /**
     * 创建提问用户工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param store 提供存储能力的依赖，具体实现由当前组件的组装方传入。
     */
    public AskUserTool(AskUserStore store) {
        super(
                ToolBase.builder()
                        .name("ask_user")
                        .description(
                                "向用户提出 1-10 道单选或多选题并暂停本轮。每题必须提供 questionId、title、type(single|multiple)、required 和"
                                        + " 2-10 个带 optionId/label 的 options；得到回答后工具会返回 answers JSON。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "questions",
                                                Map.of(
                                                        "type",
                                                        "array",
                                                        "minItems",
                                                        1,
                                                        "maxItems",
                                                        10,
                                                        "items",
                                                        Map.of(
                                                                "type",
                                                                "object",
                                                                "required",
                                                                List.of(
                                                                        "questionId",
                                                                        "type",
                                                                        "title",
                                                                        "options"),
                                                                "properties",
                                                                Map.of(
                                                                        "questionId",
                                                                        Map.of("type", "string"),
                                                                        "type",
                                                                        Map.of(
                                                                                "enum",
                                                                                List.of(
                                                                                        "single",
                                                                                        "multiple")),
                                                                        "title",
                                                                        Map.of("type", "string"),
                                                                        "required",
                                                                        Map.of("type", "boolean"),
                                                                        "options",
                                                                        Map.of(
                                                                                "type",
                                                                                "array",
                                                                                "minItems",
                                                                                2,
                                                                                "maxItems",
                                                                                10,
                                                                                "items",
                                                                                Map.of(
                                                                                        "type",
                                                                                        "object",
                                                                                        "required",
                                                                                        List.of(
                                                                                                "optionId",
                                                                                                "label"),
                                                                                        "properties",
                                                                                        Map.of(
                                                                                                "optionId",
                                                                                                Map
                                                                                                        .of(
                                                                                                                "type",
                                                                                                                "string"),
                                                                                                "label",
                                                                                                Map
                                                                                                        .of(
                                                                                                                "type",
                                                                                                                "string"),
                                                                                                "description",
                                                                                                Map
                                                                                                        .of(
                                                                                                                "type",
                                                                                                                "string")))))))),
                                        "required",
                                        List.of("questions"),
                                        "additionalProperties",
                                        false))
                        .readOnly(true)
                        .concurrencySafe(true));
        this.store = store;
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param p 当前提问用户工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
        var ctx = p.getRuntimeContext();
        var execution = ctx.get(ArtifactExecutionContext.class);
        String call = p.getToolUseBlock().getId();
        Object questions = p.getInput().get("questions");
        if (!(questions instanceof List<?> list) || list.isEmpty() || list.size() > 10)
            return Mono.just(ToolResultBlock.error("questions must contain 1-10 items"));
        try {
            String json = JSON.writeValueAsString(validate(list));
            Instant now = Instant.now();
            String id =
                    "ask_"
                            + UUID.nameUUIDFromBytes(
                                            (ctx.getUserId()
                                                            + "\0"
                                                            + execution.getTurnId()
                                                            + "\0"
                                                            + call)
                                                    .getBytes())
                                    .toString()
                                    .replace("-", "");
            AskUserRequest request =
                    store.createOrFind(
                            new AskUserRequest(
                                    ctx.getUserId(),
                                    ctx.getSessionId(),
                                    execution.getTurnId(),
                                    id,
                                    null,
                                    call,
                                    json,
                                    "[]",
                                    AskUserStatus.PENDING,
                                    now,
                                    now.plusSeconds(1800),
                                    null,
                                    0));
            AskUserEventCollector collector = ctx.get(AskUserEventCollector.class);
            if (collector != null) collector.record(request);
            // 返回暂停块可将调用保留在 AgentScope 的待处理工具状态。响应式工具抛出异常可靠性较低，
            // 执行基础设施可能在 ReAct 接收前将异步异常转换为普通工具错误。
            return Mono.just(ToolResultBlock.suspended(p.getToolUseBlock()));
        } catch (Exception e) {
            return Mono.just(
                    ToolResultBlock.error("invalid ask_user questions: " + e.getMessage()));
        }
    }

    /**
     * 校验当前提问用户工具的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @param questions 本次澄清请求包含的问题列表。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static List<Map<String, Object>> validate(List<?> questions) {
        HashSet<String> questionIds = new HashSet<>();
        ArrayList<Map<String, Object>> normalized = new ArrayList<>();
        for (Object raw : questions) {
            if (!(raw instanceof Map<?, ?> q))
                throw new IllegalArgumentException("question must be object");
            String questionId = text(q.get("questionId"), "questionId", 64);
            if (!questionIds.add(questionId))
                throw new IllegalArgumentException("questionId must be unique");
            String type = text(q.get("type"), "type", 16).toLowerCase(Locale.ROOT);
            if (!type.equals("single") && !type.equals("multiple"))
                throw new IllegalArgumentException("type must be single or multiple");
            String title = text(q.get("title"), "title", 500);
            Object opts = q.get("options");
            if (!(opts instanceof List<?> options) || options.size() < 2 || options.size() > 10)
                throw new IllegalArgumentException("each question needs 2-10 options");
            HashSet<String> optionIds = new HashSet<>();
            ArrayList<Map<String, String>> values = new ArrayList<>();
            for (Object option : options) {
                if (!(option instanceof Map<?, ?> value))
                    throw new IllegalArgumentException("option must be object");
                String optionId = text(value.get("optionId"), "optionId", 64);
                if (!optionIds.add(optionId))
                    throw new IllegalArgumentException("optionId must be unique within a question");
                LinkedHashMap<String, String> next = new LinkedHashMap<>();
                next.put("optionId", optionId);
                next.put("label", text(value.get("label"), "option label", 500));
                Object description = value.get("description");
                if (description != null && !String.valueOf(description).isBlank())
                    next.put("description", text(description, "option description", 1000));
                values.add(next);
            }
            LinkedHashMap<String, Object> next = new LinkedHashMap<>();
            next.put("questionId", questionId);
            next.put("type", type);
            next.put("title", title);
            next.put("required", Boolean.TRUE.equals(q.get("required")));
            next.put("options", values);
            normalized.add(next);
        }
        return List.copyOf(normalized);
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前提问用户工具使用的字段，供其处理与状态记录使用。
     * @param max 当前提问用户工具使用的最大，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String text(Object value, String field, int max) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty() || text.length() > max)
            throw new IllegalArgumentException(field + " must be 1-" + max + " characters");
        return text;
    }
}
