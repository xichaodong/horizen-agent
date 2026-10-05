package dev.horizen.agent.web.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** SSE 连接、发送缓冲和共享写线程的资源上限。 */
@ConfigurationProperties(prefix = "horizen.agent.sse")
@Data
public class SseProperties {
    /** 发送保活心跳或检查租约的时间间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    /** 排队等待允许持续的最长时间。 */
    private Duration maxQueueWait = Duration.ofSeconds(10);

    /** 允许同时保留的 SSE 连接数量上限。 */
    private int maxConnections = 512;

    /** 单个连接待发送事件的数量上限。 */
    private int outboundMaxEvents = 256;

    /** 单个连接待发送内容的字节数上限。 */
    private int outboundMaxBytes = 256 * 1024;

    /** SSE 异步写入线程池保留的核心线程数量。 */
    private int writerCoreThreads = 4;

    /** SSE 异步写入线程池允许使用的最大线程数量。 */
    private int writerMaxThreads = 16;

    /** SSE 异步写入线程池允许排队的任务数量上限。 */
    private int writerQueueCapacity = 512;

    /**
     * 校验当前SSE配置的输入与状态约束，不满足条件时拒绝继续处理。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void validate() {
        if (heartbeatInterval == null
                || heartbeatInterval.isNegative()
                || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("sse.heartbeatInterval 必须为正数");
        }
        if (maxQueueWait == null || maxQueueWait.isNegative() || maxQueueWait.isZero()) {
            throw new IllegalArgumentException("sse.maxQueueWait 必须为正数");
        }
        if (maxConnections <= 0
                || outboundMaxEvents <= 0
                || outboundMaxBytes <= 0
                || writerCoreThreads <= 0
                || writerMaxThreads < writerCoreThreads
                || writerQueueCapacity <= 0) {
            throw new IllegalArgumentException("SSE 资源上限配置无效");
        }
    }
}
