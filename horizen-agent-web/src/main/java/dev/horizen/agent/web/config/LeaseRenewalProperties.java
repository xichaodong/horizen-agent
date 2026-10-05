package dev.horizen.agent.web.config;

import dev.horizen.agent.application.turn.LeaseRenewalPolicy;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 实例级 Turn 批量续租的分批、并发和有限重试边界。 */
@ConfigurationProperties(prefix = "horizen.agent.storage.lease-renewal")
@Data
public class LeaseRenewalProperties {
    /** 单个处理批次允许包含的最大项目数量。 */
    private int batchSize = 50;

    /** 同时执行当前处理步骤的并发数量上限。 */
    private int concurrency = 2;

    /** 首次尝试失败后允许的额外重试次数。 */
    private int retries = 1;

    /** 重试延迟的时间配置，供等待、调度或失效判断使用。 */
    private Duration retryDelay = Duration.ofSeconds(1);

    /** 查询允许持续的最长等待时间。 */
    private Duration queryTimeout = Duration.ofSeconds(3);

    /** 连接取得租约允许持续的最长等待时间。 */
    private Duration connectionAcquireTimeout = Duration.ofSeconds(3);

    /**
     * 转换为策略。
     *
     * @return 本次操作返回的租约续期策略结果。
     */
    public LeaseRenewalPolicy toPolicy() {
        validate();
        return new LeaseRenewalPolicy(batchSize, concurrency, retries, retryDelay);
    }

    /**
     * 校验当前租约续期配置的输入与状态约束，不满足条件时拒绝继续处理。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void validate() {
        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException("leaseRenewal.batchSize 必须在 1 到 1000 之间");
        }
        if (concurrency <= 0 || concurrency > 16) {
            throw new IllegalArgumentException("leaseRenewal.concurrency 必须在 1 到 16 之间");
        }
        if (retries < 0
                || retries > 3
                || retryDelay == null
                || retryDelay.isNegative()
                || retryDelay.isZero()) {
            throw new IllegalArgumentException("leaseRenewal 重试配置无效");
        }
        if (queryTimeout == null || queryTimeout.isNegative() || queryTimeout.isZero()) {
            throw new IllegalArgumentException("leaseRenewal.queryTimeout 必须为正数");
        }
        if (connectionAcquireTimeout == null
                || connectionAcquireTimeout.compareTo(Duration.ofMillis(250)) < 0) {
            throw new IllegalArgumentException("leaseRenewal.connectionAcquireTimeout 不能小于 250ms");
        }
    }
}
