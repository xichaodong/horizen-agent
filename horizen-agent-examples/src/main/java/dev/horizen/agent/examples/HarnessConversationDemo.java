package dev.horizen.agent.examples;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import java.nio.file.Paths;
import java.time.Duration;

/** 真实模型的两轮对话示例；凭据只从进程环境变量读取。 */
public final class HarnessConversationDemo {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private HarnessConversationDemo() {}

    /**
     * 完成当前操作的main步骤，按实现更新相应状态或依赖。
     *
     * @param args 当前Harness对话演示持有的参数集合对象，供相应处理步骤使用。
     */
    public static void main(String[] args) {
        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .apiKey(requireEnvironment("ARK_API_KEY"))
                        .baseUrl(
                                environmentOr(
                                        "ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"))
                        .modelName(environmentOr("ARK_MODEL", "deepseek-v4-1-flash-260910"))
                        .stream(true)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("note-taker")
                        .sysPrompt("你是一个帮助用户做笔记的助手。")
                        .model(model)
                        .workspace(Paths.get(".agentscope/workspace"))
                        .disableShellTool()
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(30)
                                        .keepMessages(10)
                                        .build())
                        .build()) {
            RuntimeContext context =
                    RuntimeContext.builder().sessionId("demo-session").userId("demo-owner").build();
            printReply(
                    agent.call(new UserMessage("我叫天宇，今天准备一个关于 ReAct 的技术分享。"), context)
                            .block(Duration.ofMinutes(2)));
            printReply(
                    agent.call(new UserMessage("我叫什么？我今天要干什么？"), context)
                            .block(Duration.ofMinutes(2)));
        }
    }

    /**
     * 完成当前操作的printReply步骤，按实现更新相应状态或依赖。
     *
     * @param reply 本次执行返回的文本回复，供宿主消息接口输出。
     */
    private static void printReply(Msg reply) {
        if (reply != null) {
            System.out.println("助手：" + reply.getTextContent());
        }
    }

    /**
     * 取得并校验环境。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("请先配置环境变量 " + name);
        }
        return value.trim();
    }

    /**
     * 生成当前操作所需的environmentOr文本，供调用方继续处理。
     *
     * @param name 需要定位或处理的名称。
     * @param fallback 当前Harness对话演示使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String environmentOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
