package dev.horizen.agent.application.turn;

import lombok.Value;

import java.time.Duration;
import java.util.Objects;

/** 单次执行的超时与运行控制策略，独立于 HTTP 配置绑定。 */
@Value
public class TurnExecutionPolicy {
    /** 单次执行流允许持续的最长时间。 */
    Duration streamTimeout;

    /** 执行流没有产生新事件时允许的最长空闲时间。 */
    Duration idleTimeout;

    /** 执行实例租约的有效时长。 */
    Duration leaseTtl;

    /** 当前服务实例标识，用于区分分布式执行与资源统计。 */
    String instanceId;

    /**
     * 创建执行执行策略，初始化该组件所需的状态、配置或依赖。
     *
     * @param streamTimeout 单次执行流允许持续的最长时间。
     * @param idleTimeout 执行流没有产生新事件时允许的最长空闲时间。
     * @param leaseTtl 执行实例租约的有效时长。
     * @param instanceId 当前服务实例标识，用于区分分布式执行与资源统计。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public TurnExecutionPolicy(
            Duration streamTimeout, Duration idleTimeout, Duration leaseTtl, String instanceId) {
        this.streamTimeout = positive(streamTimeout, "streamTimeout");
        this.idleTimeout = positive(idleTimeout, "idleTimeout");
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        if (instanceId != null && instanceId.isBlank())
            throw new IllegalArgumentException("instanceId must not be blank");
        this.instanceId = instanceId;
    }

    /**
     * 计算或取得本方法声明的结果，供当前TurnExecutionPolicy处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name 需要定位或处理的名称。
     * @return 本次操作返回的耗时结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative())
            throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
