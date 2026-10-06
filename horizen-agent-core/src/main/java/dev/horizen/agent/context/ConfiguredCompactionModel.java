package dev.horizen.agent.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 只用于上下文摘要的模型包装，统一限制输出长度、随机性和调用超时。
 */
public final class ConfiguredCompactionModel implements Model {
    /**
     * 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    private final Model delegate;

    /**
     * 创建当前模型或沙箱对象时使用的默认策略配置。
     */
    private final GenerateOptions defaults;

    /**
     * 创建已配置压缩模型，初始化该组件所需的状态、配置或依赖。
     *
     * @param delegate        被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     * @param maxOutputTokens 最大输出的 token 数量或预算。
     * @param temperature     当前已配置压缩模型使用的temperature，供其处理与状态记录使用。
     * @param timeout         本次等待允许持续的最长时间。
     * @param maxAttempts     当前已配置压缩模型使用的最大尝试次数，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ConfiguredCompactionModel(
            Model delegate,
            int maxOutputTokens,
            double temperature,
            Duration timeout,
            int maxAttempts) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must not be negative");
        }
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature must be in [0, 2]");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        GenerateOptions.Builder builder =
                GenerateOptions.builder()
                        .temperature(temperature)
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(timeout)
                                        .maxAttempts(maxAttempts)
                                        .build());
        if (maxOutputTokens > 0) {
            builder.maxTokens(maxOutputTokens);
        }
        this.defaults = builder.build();
    }

    /**
     * 产生执行流并返回已配置压缩模型。
     *
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param tools    工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param options  可供当前请求选择的选项或策略集合。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return delegate.stream(messages, tools, GenerateOptions.mergeOptions(options, defaults));
    }

    /**
     * 读取模型名称。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /**
     * 判断是否支持原生结构化输出。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    /**
     * 判断是否支持原生结构化输出配置工具集合。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    /**
     * 读取上下文窗口大小。
     *
     * @return 本次操作返回的整数结果。
     */
    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
