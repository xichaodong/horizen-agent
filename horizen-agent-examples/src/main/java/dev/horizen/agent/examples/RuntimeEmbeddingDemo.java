package dev.horizen.agent.examples;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;

import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 面向宿主的最小 Runtime 示例，无需模型凭据或外部服务。
 */
public final class RuntimeEmbeddingDemo {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private RuntimeEmbeddingDemo() {
    }

    /**
     * 完成当前操作的main步骤，按实现更新相应状态或依赖。
     *
     * @param args 当前运行时Embedding演示持有的参数集合对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void main(String[] args) {
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("embedded-demo")
                        .model(new DemoModel())
                        .workspace(Path.of(".agentscope", "embedding-demo"))
                        .stateStore(new InMemoryAgentStateStore())
                        .maxIters(2)
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        try (HarnessAgentRuntime runtime = new HarnessAgentRuntime(agent)) {
            AgentTurnRequest request =
                    new AgentTurnRequest(
                            UUID.randomUUID().toString(),
                            "demo-owner",
                            "demo-session",
                            "Hello, embedded runtime!",
                            List.of(),
                            List.of(),
                            List.of());
            var events =
                    runtime.stream(request)
                            .doOnNext(
                                    event ->
                                            System.out.println(
                                                    event.getType() + ": " + event.getText()))
                            .collectList()
                            .block(Duration.ofSeconds(15));
            if (events == null
                    || events.stream()
                    .noneMatch(
                            event ->
                                    event.getType()
                                            == AgentRuntimeEvent.Type.TURN_COMPLETED)) {
                throw new IllegalStateException("Embedded demo did not complete its Turn");
            }
        }
    }

    /**
     * 无需远端凭据的确定性演示模型，供本地运行链路与示例使用。
     */
    private static final class DemoModel extends ChatModelBase {
        /**
         * 读取模型名称。
         *
         * @return 本次处理生成或读取的文本。
         */
        @Override
        public String getModelName() {
            return "scripted-embedding";
        }

        /**
         * 计算或取得本方法声明的结果，供当前DemoModel处理步骤使用。
         *
         * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @param tools    工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @param options  可供当前请求选择的选项或策略集合。
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text(
                                                            "Hello from the embedded Horizen runtime.")
                                                    .build()))
                            .build());
        }
    }
}
