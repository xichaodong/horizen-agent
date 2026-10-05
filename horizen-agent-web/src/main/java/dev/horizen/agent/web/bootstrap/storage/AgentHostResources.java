package dev.horizen.agent.web.bootstrap.storage;

import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import lombok.RequiredArgsConstructor;

/** 容器管理资源的状态视图；资源销毁由 Spring 负责。 */
@RequiredArgsConstructor
public final class AgentHostResources {
    /** 本组件使用的 {@code RuntimeStorage} 状态或依赖，用于 storage 的处理。 */
    private final RuntimeStorage storage;

    /** 当前执行或历史事件集合，供持久化、回放与观测使用。 */
    private final RedisTurnEventBridge events;

    /**
     * 产生执行流并返回事件状态。
     *
     * @return 本次操作返回的事件桥接器状态结果。
     */
    public RedisTurnEventBridge.EventBridgeStatus streamEventStatus() {
        return events == null ? new RedisTurnEventBridge.EventBridgeStatus() : events.status();
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentHostResources处理步骤使用。
     *
     * @return 本次操作返回的数据库连接池状态结果。
     */
    public RuntimeStorage.DatabasePoolStatus databasePoolStatus() {
        return storage == null
                ? new RuntimeStorage.DatabasePoolStatus()
                : storage.databasePoolStatus();
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentHostResources处理步骤使用。
     *
     * @return 本次操作返回的Redis连接池集合状态结果。
     */
    public RuntimeStorage.RedisPoolsStatus redisPoolsStatus() {
        return storage == null ? new RuntimeStorage.RedisPoolsStatus() : storage.redisPoolsStatus();
    }
}
