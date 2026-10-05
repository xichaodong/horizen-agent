package dev.horizen.agent.web.bootstrap;

import dev.horizen.agent.web.config.SseProperties;
import dev.horizen.agent.web.stream.SseConnectionManager;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 由容器管理的容量受限写入线程池和共享单线程心跳调度器。 */
@Configuration(proxyBeanMethods = false)
public class SseConfiguration {
    /**
     * 计算或取得本方法声明的结果，供当前SseConfiguration处理步骤使用。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的线程连接池任务执行方结果。
     */
    @Bean("sseWriterExecutor")
    ThreadPoolTaskExecutor writers(SseProperties properties) {
        properties.validate();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWriterCoreThreads());
        executor.setMaxPoolSize(properties.getWriterMaxThreads());
        executor.setQueueCapacity(properties.getWriterQueueCapacity());
        executor.setThreadNamePrefix("horizen-sse-writer-");
        executor.setDaemon(true);
        executor.setAwaitTerminationSeconds(3);
        return executor;
    }

    /**
     * 计算或取得本方法声明的结果，供当前SseConfiguration处理步骤使用。
     *
     * @return 本次操作返回的线程连接池任务Scheduler结果。
     */
    @Bean("sseHeartbeatScheduler")
    ThreadPoolTaskScheduler heartbeats() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("horizen-sse-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(3);
        return scheduler;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param writers 当前SSE组装持有的writers对象，供相应处理步骤使用。
     * @param scheduler 周期任务的调度器，用于心跳、轮询或续租等定时工作。
     * @return 本次操作返回的SSE连接管理器结果。
     */
    @Bean(destroyMethod = "close")
    SseConnectionManager connections(
            SseProperties properties,
            @Qualifier("sseWriterExecutor") ThreadPoolTaskExecutor writers,
            @Qualifier("sseHeartbeatScheduler") ThreadPoolTaskScheduler scheduler) {
        return new SseConnectionManager(
                properties, scheduler.getScheduledExecutor(), writers.getThreadPoolExecutor());
    }
}
