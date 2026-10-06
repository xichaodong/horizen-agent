package dev.horizen.agent.web.bootstrap.model;

import dev.horizen.agent.adapter.agentscope.runtime.ApprovalPresentationProvider;
import dev.horizen.agent.adapter.agentscope.runtime.SubagentInteractionMiddleware;
import dev.horizen.agent.runtime.api.ApprovalPresentation;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 双实例黑盒验收使用的确定性模型，不访问外部模型服务。
 */
public final class ScriptedWebModel extends ChatModelBase {

    /**
     * 读取模型名称。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String getModelName() {
        return "scripted-web";
    }

    /**
     * 计算或取得本方法声明的结果，供当前ScriptedWebModel处理步骤使用。
     *
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param tools    工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param options  可供当前请求选择的选项或策略集合。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        var handoff =
                messages.stream()
                        .filter(
                                message ->
                                        SubagentInteractionMiddleware.REQUEST_MESSAGE.equals(
                                                message.getName()))
                        .reduce((a, b) -> b);
        if (handoff.isPresent()) {
            Map<?, ?> body =
                    JsonUtils.getJsonCodec().fromJson(handoff.get().getTextContent(), Map.class);
            Map<?, ?> request = (Map<?, ?>) ((List<?>) body.get("requests")).get(0);
            String name = request.get("toolName").toString();
            boolean completed =
                    messages.stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .anyMatch(result -> name.equals(result.getName()));
            if (!completed) {
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = (Map<String, Object>) request.get("input");
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                ToolUseBlock.builder()
                                                        .id("scripted-parent-action-call")
                                                        .name(name)
                                                        .input(arguments)
                                                        .content(
                                                                JsonUtils.getJsonCodec()
                                                                        .toJson(arguments))
                                                        .build()))
                                .build());
            }
        }
        String toolResult =
                messages.stream()
                        .flatMap(
                                message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .flatMap(result -> result.getOutput().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .reduce((left, right) -> right)
                        .orElse(null);
        if (toolResult != null) {
            return text("scripted-tool:" + toolResult);
        }
        String input = latestUserText(messages);
        if (input.startsWith("delegate")) {
            String task =
                    input.contains("approval")
                            ? "child approval delegated task"
                            : input.contains("clarification")
                            ? "child ask delegated task"
                            : "child delegated task";
            Map<String, Object> arguments =
                    Map.of("agent_id", "general_worker", "task", task, "timeout_seconds", 30);
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("scripted-delegation-call")
                                                    .name("agent_spawn")
                                                    .input(arguments)
                                                    .content(
                                                            JsonUtils.getJsonCodec()
                                                                    .toJson(arguments))
                                                    .build()))
                            .build());
        }
        if (input.startsWith("approval") || input.startsWith("child approval")) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("scripted-approval-call")
                                                    .name("scripted_dangerous_action")
                                                    .input(Map.of("value", input))
                                                    .content("{\"value\":\"" + input + "\"}")
                                                    .build()))
                            .build());
        }
        if (input.startsWith("ask") || input.startsWith("child ask")) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("scripted-ask-call")
                                                    .name("ask_user")
                                                    .input(
                                                            Map.of(
                                                                    "questions",
                                                                    List.of(
                                                                            Map.of(
                                                                                    "questionId",
                                                                                    "scope",
                                                                                    "type",
                                                                                    "single",
                                                                                    "title",
                                                                                    "请选择范围",
                                                                                    "required",
                                                                                    true,
                                                                                    "options",
                                                                                    List.of(
                                                                                            Map.of(
                                                                                                    "optionId",
                                                                                                    "week",
                                                                                                    "label",
                                                                                                    "最近一周"),
                                                                                            Map.of(
                                                                                                    "optionId",
                                                                                                    "month",
                                                                                                    "label",
                                                                                                    "最近一月"))))))
                                                    .content(
                                                            """
                                                                    {"questions":[{"questionId":"scope","type":"single","title":"请选择范围","required":true,"options":[{"optionId":"week","label":"最近一周"},{"optionId":"month","label":"最近一月"}]}]}
                                                                    """)
                                                    .build()))
                            .build());
        }
        if (input.startsWith("todo")) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("scripted-todo-call")
                                                    .name("todo_write")
                                                    .input(
                                                            Map.of(
                                                                    "todos",
                                                                    List.of(
                                                                            Map.of(
                                                                                    "content",
                                                                                    "核验经营数据",
                                                                                    "status",
                                                                                    "completed",
                                                                                    "priority",
                                                                                    "high"),
                                                                            Map.of(
                                                                                    "content",
                                                                                    "输出诊断结论",
                                                                                    "status",
                                                                                    "in_progress",
                                                                                    "priority",
                                                                                    "medium"))))
                                                    .content(
                                                            """
                                                                    {"todos":[{"content":"核验经营数据","status":"completed","priority":"high"},{"content":"输出诊断结论","status":"in_progress","priority":"medium"}]}
                                                                    """)
                                                    .build()))
                            .build());
        }
        if (input.startsWith("provider")
                && tools.stream()
                .anyMatch(tool -> "acceptance_provider_tool".equals(tool.getName()))) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(
                                            ToolUseBlock.builder()
                                                    .id("scripted-provider-call")
                                                    .name("acceptance_provider_tool")
                                                    .input(Map.of("query", input))
                                                    .content("{\"query\":\"" + input + "\"}")
                                                    .build()))
                            .build());
        }
        if (input.startsWith("wait")) {
            return Mono.delay(Duration.ofMinutes(5))
                    .flatMapMany(ignored -> text("scripted:" + input));
        }
        if (input.startsWith("stream")) {
            Duration gap =
                    input.startsWith("stream slow") ? Duration.ofSeconds(5) : Duration.ofSeconds(2);
            return Flux.concat(
                    Mono.delay(Duration.ofMillis(80)).flatMapMany(ignored -> text("第一段，")),
                    Mono.delay(gap).flatMapMany(ignored -> text("第二段，")),
                    Mono.delay(gap).flatMapMany(ignored -> text("第三段。")));
        }
        return text("scripted:" + input);
    }

    /**
     * 生成当前操作所需的latestUserText文本，供调用方继续处理。
     *
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理生成或读取的文本。
     */
    private static String latestUserText(List<Msg> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (message.getRole() == MsgRole.USER) {
                return message.getTextContent();
            }
        }
        return "";
    }

    /**
     * 计算或取得本方法声明的结果，供当前ScriptedWebModel处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    private static Flux<ChatResponse> text(String value) {
        return Flux.just(
                ChatResponse.builder()
                        .content(List.<ContentBlock>of(TextBlock.builder().text(value).build()))
                        .build());
    }

    /**
     * 脚本Web模型内部的审批工具，封装该步骤需要的状态或输入输出。
     */
    public static final class ApprovalTool extends ToolBase
            implements ApprovalPresentationProvider {
        /**
         * 创建审批工具，初始化该组件所需的状态、配置或依赖。
         */
        public ApprovalTool() {
            super(
                    ToolBase.builder()
                            .name("scripted_dangerous_action")
                            .description("Deterministic action for distributed acceptance tests.")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of("value", Map.of("type", "string")),
                                            "required",
                                            List.of("value"),
                                            "additionalProperties",
                                            false))
                            .readOnly(false)
                            .concurrencySafe(true));
        }

        /**
         * 构造并返回当前操作所需的结果对象。
         *
         * @param input   本次处理的输入。
         * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
         * @return 本次操作返回的审批呈现结果。
         */
        @Override
        public ApprovalPresentation describeApproval(
                Map<String, Object> input, RuntimeContext context) {
            return new ApprovalPresentation(
                    "确认执行演示操作",
                    "演示工具配置为执行前需要确认。",
                    "此操作仅生成模拟回执，不会修改业务数据。",
                    Map.of("操作内容", String.valueOf(input.get("value")), "数据来源", "本地模拟"));
        }

        /**
         * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
         *
         * @param param 当前审批工具持有的参数对象，供相应处理步骤使用。
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("approved:" + param.getInput().get("value")));
        }
    }
}
