package dev.horizen.agent.examples;

import dev.horizen.agent.observability.AgentEventObserver;
import dev.horizen.agent.observability.JsonlTraceSink;
import dev.horizen.agent.observability.horizen.HorizenHttpBatchExporter;
import dev.horizen.agent.observability.horizen.HorizenTraceConfig;
import dev.horizen.agent.observability.horizen.HorizenTraceContext;
import dev.horizen.agent.observability.horizen.HorizenTracingMiddleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用脚本模型和进程内只读工具运行真实 AgentScope 循环。 */
public final class ObservabilityDemo {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private ObservabilityDemo() {}

    /**
     * 完成当前操作的main步骤，按实现更新相应状态或依赖。
     *
     * @param args 当前Observability演示持有的参数集合对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void main(String[] args) throws Exception {
        String turnId = UUID.randomUUID().toString();
        String sessionId = "demo-" + turnId;
        Path output =
                args.length > 0 ? Path.of(args[0]) : Path.of("target", "traces", turnId + ".jsonl");
        boolean fail = Arrays.asList(args).contains("fail");
        boolean allowHorizen = !Arrays.asList(args).contains("no-horizen");
        var horizenConfig =
                allowHorizen
                        ? HorizenTraceConfig.fromEnvironment()
                        : Optional.<HorizenTraceConfig>empty();
        HorizenHttpBatchExporter horizenExporter =
                horizenConfig.map(HorizenHttpBatchExporter::new).orElse(null);
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new EchoTool());
        var agentBuilder =
                ReActAgent.builder()
                        .name("horizen-demo")
                        .sysPrompt("Use the echo tool to repeat the requested text.")
                        .model(new ScriptedModel(fail))
                        .toolkit(toolkit)
                        .maxIters(3)
                        .maxRetries(1);
        horizenConfig.ifPresent(
                config ->
                        agentBuilder.middleware(
                                new HorizenTracingMiddleware(config, horizenExporter)));
        ReActAgent agent = agentBuilder.build();

        System.out.println("Scripted model demo: no model API key or external service is used.");
        System.out.println("Turn/trace ID: " + turnId);
        System.out.println("Trace: " + output.toAbsolutePath());
        if (horizenExporter != null) {
            System.out.println("Horizen export enabled: " + horizenExporter.getEndpoint());
        }
        try (JsonlTraceSink sink = new JsonlTraceSink(output)) {
            // 此演示仅包含合成文本，并显式启用内容采集。
            AgentEventObserver observer = new AgentEventObserver(turnId, sessionId, sink, true);
            RuntimeContext.Builder runtimeContext = RuntimeContext.builder().sessionId(sessionId);
            if (horizenExporter != null) {
                runtimeContext.put(
                        HorizenTraceContext.class,
                        new HorizenTraceContext(
                                turnId,
                                turnId,
                                sessionId,
                                null,
                                "horizen-agent-demo",
                                Map.of("demo", true, "invocationKind", "acceptance")));
            }
            var events =
                    agent.streamEvents(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("Hello, Horizen!")
                                                    .build()),
                                    runtimeContext.build())
                            .doOnNext(observer)
                            .doOnError(observer::onError)
                            .doOnCancel(observer::onCancel)
                            .collectList()
                            .block(Duration.ofSeconds(15));
            if (events == null) {
                throw new IllegalStateException("Agent produced no events");
            }
            events.stream()
                    .filter(AgentResultEvent.class::isInstance)
                    .map(AgentResultEvent.class::cast)
                    .forEach(event -> System.out.println(event.getResult().getTextContent()));
        } finally {
            if (horizenExporter != null) {
                horizenExporter.close();
                System.out.printf(
                        "Horizen export: uploaded=%d failed=%d dropped=%d%n",
                        horizenExporter.uploadedCount(),
                        horizenExporter.failedCount(),
                        horizenExporter.droppedCount());
                if (horizenExporter.lastFailure() != null) {
                    System.out.println("Horizen last failure: " + horizenExporter.lastFailure());
                }
            }
        }
    }

    /** 无需远端凭据的确定性演示模型，供本地运行链路与示例使用。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class ScriptedModel extends ChatModelBase {
        /** 调用集合的原子状态，供并发更新与统计读取使用。 */
        private final AtomicInteger calls = new AtomicInteger();

        /** fail的状态标记，用于选择当前组件的处理路径。 */
        private final boolean fail;

        /**
         * 读取模型名称。
         *
         * @return 本次处理生成或读取的文本。
         */
        @Override
        public String getModelName() {
            return "scripted-demo";
        }

        /**
         * 计算或取得本方法声明的结果，供当前ScriptedModel处理步骤使用。
         *
         * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @param tools 工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @param options 可供当前请求选择的选项或策略集合。
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        if (fail) {
                            return Flux.error(new IllegalStateException("Deliberate demo failure"));
                        }
                        if (calls.getAndIncrement() == 0) {
                            return Flux.just(
                                    ChatResponse.builder()
                                            .usage(new ChatUsage(10, 3, 0.001))
                                            .content(
                                                    List.<ContentBlock>of(
                                                            ToolUseBlock.builder()
                                                                    .id("demo-echo-1")
                                                                    .name("echo")
                                                                    .input(
                                                                            Map.of(
                                                                                    "text",
                                                                                    "Hello, Horizen!"))
                                                                    // 流式工具调用同时携带 JSON
                                                                    // 参数负载。
                                                                    .content(
                                                                            "{\"text\":\"Hello, Horizen!\"}")
                                                                    .build()))
                                            .build());
                        }
                        String text =
                                messages.stream()
                                        .flatMap(
                                                message ->
                                                        message
                                                                .getContentBlocks(
                                                                        ToolResultBlock.class)
                                                                .stream())
                                        .flatMap(result -> result.getOutput().stream())
                                        .filter(TextBlock.class::isInstance)
                                        .map(TextBlock.class::cast)
                                        .map(TextBlock::getText)
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "Missing tool result"));
                        return Flux.just(
                                ChatResponse.builder()
                                        .usage(new ChatUsage(10, 3, 0.001))
                                        .content(
                                                List.<ContentBlock>of(
                                                        TextBlock.builder()
                                                                .text("Tool returned: " + text)
                                                                .build()))
                                        .build());
                    });
        }
    }

    /** 只读的演示回显工具，用于验证模型与工具调用链路。 */
    private static final class EchoTool implements AgentTool {
        /**
         * 读取名称。
         *
         * @return 本次处理生成或读取的文本。
         */
        @Override
        public String getName() {
            return "echo";
        }

        /**
         * 读取说明。
         *
         * @return 本次处理生成或读取的文本。
         */
        @Override
        public String getDescription() {
            return "Return the supplied text unchanged. This tool has no external side effects.";
        }

        /**
         * 读取参数集合。
         *
         * @return 按返回类型约定组织的结果映射。
         */
        @Override
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("text", Map.of("type", "string")),
                    "required",
                    List.of("text"));
        }

        /**
         * 判断读取只读。
         *
         * @return 本次检查是否通过或本次更新是否成功。
         */
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /**
         * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
         *
         * @param param 当前Echo工具持有的参数对象，供相应处理步骤使用。
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text((String) param.getInput().get("text")));
        }
    }
}
