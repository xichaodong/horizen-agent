package dev.horizen.agent.application.turn;

import lombok.Value;

import java.time.Duration;
import java.util.Objects;

/**
 * 宿主身份和恢复调度周期。
 */
@Value
public class TurnRecoveryPolicy {
    /**
     * 当前服务实例标识，用于区分分布式执行与资源统计。
     */
    String instanceId;

    /**
     * 跨实例执行控制消息的轮询间隔。
     */
    Duration controlPollInterval;

    /**
     * 执行租约续期的调度间隔。
     */
    Duration leaseHeartbeat;

    /**
     * 创建执行恢复策略，初始化该组件所需的状态、配置或依赖。
     *
     * @param instanceId          当前服务实例标识，用于区分分布式执行与资源统计。
     * @param controlPollInterval 跨实例执行控制消息的轮询间隔。
     * @param leaseHeartbeat      执行租约续期的调度间隔。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public TurnRecoveryPolicy(
            String instanceId, Duration controlPollInterval, Duration leaseHeartbeat) {
        if (instanceId == null || instanceId.isBlank())
            throw new IllegalArgumentException("instanceId must not be blank");
        this.instanceId = instanceId;
        this.controlPollInterval = positive(controlPollInterval);
        this.leaseHeartbeat = positive(leaseHeartbeat);
    }

    /**
     * 计算或取得本方法声明的结果，供当前TurnRecoveryPolicy处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的耗时结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "interval");
        if (value.isNegative() || value.isZero())
            throw new IllegalArgumentException("interval must be positive");
        return value;
    }
}
