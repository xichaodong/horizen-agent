package dev.horizen.agent.application.turn;

import lombok.Value;

import java.time.Duration;
import java.util.Objects;

/**
 * 容量受限的续租任务，不依赖宿主配置绑定。
 */
@Value
public class LeaseRenewalPolicy {
    /**
     * 单个处理批次允许包含的最大项目数量。
     */
    int batchSize;

    /**
     * 同时执行当前处理步骤的并发数量上限。
     */
    int concurrency;

    /**
     * 首次尝试失败后允许的额外重试次数。
     */
    int retries;

    /**
     * 重试延迟的时间配置，供等待、调度或失效判断使用。
     */
    Duration retryDelay;

    /**
     * 创建租约续期策略，初始化该组件所需的状态、配置或依赖。
     *
     * @param batchSize   当前租约续期策略使用的批次大小，供其处理与状态记录使用。
     * @param concurrency 当前租约续期策略使用的并发，供其处理与状态记录使用。
     * @param retries     当前租约续期策略使用的重试，供其处理与状态记录使用。
     * @param retryDelay  重试延迟的时间配置，供等待、调度或失效判断使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public LeaseRenewalPolicy(int batchSize, int concurrency, int retries, Duration retryDelay) {
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("Invalid renewal batchSize");
        if (concurrency < 1 || concurrency > 16)
            throw new IllegalArgumentException("Invalid renewal concurrency");
        if (retries < 0 || retries > 3)
            throw new IllegalArgumentException("Invalid renewal retries");
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isZero() || retryDelay.isNegative())
            throw new IllegalArgumentException("retryDelay must be positive");
        this.batchSize = batchSize;
        this.concurrency = concurrency;
        this.retries = retries;
        this.retryDelay = retryDelay;
    }
}
