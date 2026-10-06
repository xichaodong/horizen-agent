package dev.horizen.agent.web.evaluation;

import dev.horizen.agent.evaluation.EvaluationRunService;
import dev.horizen.agent.evaluation.EvaluationSessionRegistry;
import dev.horizen.agent.web.api.AgentService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按显式配置启用云端评测执行适配器，不自动开启外部评测服务访问。
 */
@Configuration(proxyBeanMethods = false)
public class EvaluationConfiguration {
    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的评测会话注册表结果。
     */
    @Bean
    public EvaluationSessionRegistry evaluationSessions() {
        return new EvaluationSessionRegistry();
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param agent       当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param registry    当前评测组装持有的注册表对象，供相应处理步骤使用。
     * @param concurrency 当前评测组装使用的并发，供其处理与状态记录使用。
     * @return 本次操作返回的评测运行服务结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "horizen.agent.evaluation.enabled", havingValue = "true")
    public EvaluationRunService evaluationRuns(
            AgentService agent,
            EvaluationSessionRegistry registry,
            @Value("${horizen.agent.evaluation.concurrency:4}") int concurrency) {
        if (concurrency < 1 || concurrency > 32)
            throw new IllegalArgumentException("Evaluation concurrency must be 1..32");
        return new EvaluationRunService(new WebEvaluationHost(agent), registry, concurrency);
    }
}
