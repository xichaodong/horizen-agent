package dev.horizen.agent.storage.redis;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import lombok.Getter;
import lombok.experimental.Accessors;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.UnifiedJedis;

import java.time.Duration;
import java.util.Objects;

/**
 * 组合 AgentScope 官方 Redis 状态能力和项目的 Redis MessageBus。
 */
public final class RedisAgentRuntimeStore implements DistributedStore {

    /**
     * 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    private final RedisDistributedStore delegate;

    /**
     * 当前 Agent 工作上下文的状态存储。
     */
    @Getter(onMethod_ = @Override)
    @Accessors(fluent = true)
    private final AgentStateStore agentStateStore;

    /**
     * 用于队列、回放或控制信号传输的消息总线。
     */
    @Getter(onMethod_ = @Override)
    @Accessors(fluent = true)
    private final MessageBus messageBus;

    /**
     * 创建RedisAgent运行时存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param commands      当前RedisAgent运行时存储持有的命令集合对象，供相应处理步骤使用。
     * @param subscriptions 当前RedisAgent运行时存储持有的订阅集合对象，供相应处理步骤使用。
     * @param keyPrefix     存储键前缀，用于区分本应用的数据与其他使用方。
     * @param replayTtl     回放保留时间的时间配置，供等待、调度或失效判断使用。
     */
    public RedisAgentRuntimeStore(
            UnifiedJedis commands, JedisPool subscriptions, String keyPrefix, Duration replayTtl) {
        this(commands, subscriptions, keyPrefix, replayTtl, Duration.ofHours(24));
    }

    /**
     * 创建RedisAgent运行时存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param commands        当前RedisAgent运行时存储持有的命令集合对象，供相应处理步骤使用。
     * @param subscriptions   当前RedisAgent运行时存储持有的订阅集合对象，供相应处理步骤使用。
     * @param keyPrefix       存储键前缀，用于区分本应用的数据与其他使用方。
     * @param replayTtl       回放保留时间的时间配置，供等待、调度或失效判断使用。
     * @param sessionStateTtl 会话工作状态的不活跃保留时间，与持久会话历史分开管理。
     */
    public RedisAgentRuntimeStore(
            UnifiedJedis commands,
            JedisPool subscriptions,
            String keyPrefix,
            Duration replayTtl,
            Duration sessionStateTtl) {
        Objects.requireNonNull(commands, "commands");
        String prefix =
                keyPrefix == null || keyPrefix.isBlank()
                        ? "horizen-agent:"
                        : (keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":");
        this.delegate = RedisDistributedStore.fromJedis(commands, prefix);
        this.agentStateStore =
                new ExpiringAgentStateStore(
                        delegate.agentStateStore(), commands, prefix, sessionStateTtl);
        this.messageBus = new RedisMessageBus(commands, subscriptions, prefix + "bus:", replayTtl);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisAgentRuntimeStore处理步骤使用。
     *
     * @return 本次操作返回的基础存储结果。
     */
    @Override
    public BaseStore baseStore() {
        return delegate.baseStore();
    }

    /**
     * 当前沙箱保持无快照策略，不使用官方 Redis TAR 快照实现。
     */
    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new NoopSnapshotSpec();
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisAgentRuntimeStore处理步骤使用。
     *
     * @return 本次操作返回的沙箱执行防护结果。
     */
    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return delegate.sandboxExecutionGuard();
    }
}
