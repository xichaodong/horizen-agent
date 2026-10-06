package dev.horizen.agent.web.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis Turn 实时事件的轮询、写入和容量边界。
 */
@ConfigurationProperties(prefix = "horizen.agent.events")
@Data
public class TurnEventProperties {
    /**
     * 单次读取当前执行增量日志允许取得的事件数量。
     */
    private int readBatchSize = 256;

    /**
     * 读取状态或事件的基础轮询间隔。
     */
    private Duration pollInterval = Duration.ofMillis(150);

    /**
     * 无新事件时允许采用的最大轮询间隔。
     */
    private Duration maxPollInterval = Duration.ofSeconds(1);

    /**
     * 写入或发送数据允许的最长等待时间。
     */
    private Duration writeTimeout = Duration.ofSeconds(3);

    /**
     * 判断当前增量日志不存在前允许的连续空读取次数。
     */
    private int missingLogEmptyReads = 3;

    /**
     * 最大事件的字节数，用于容量或传输限制。
     */
    private int maxEventBytes = 256 * 1024;

    /**
     * 最大执行的字节数，用于容量或传输限制。
     */
    private long maxTurnBytes = 8L * 1024L * 1024L;

    /**
     * 单次执行增量日志允许保留的事件数量上限。
     */
    private int maxTurnEvents = 32_768;

    /**
     * 校验当前执行事件配置的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void validate() {
        if (readBatchSize <= 0
                || missingLogEmptyReads <= 0
                || maxEventBytes <= 0
                || maxTurnEvents <= 0
                || maxTurnBytes < maxEventBytes) {
            throw new IllegalArgumentException("Turn event 资源上限配置无效");
        }
        if (pollInterval == null
                || pollInterval.isZero()
                || pollInterval.isNegative()
                || maxPollInterval == null
                || maxPollInterval.compareTo(pollInterval) < 0
                || writeTimeout == null
                || writeTimeout.isZero()
                || writeTimeout.isNegative()) {
            throw new IllegalArgumentException("Turn event 时间配置无效");
        }
    }
}
