package dev.horizen.agent.web.bootstrap.storage;

import com.zaxxer.hikari.HikariDataSource;

import dev.horizen.agent.domain.artifact.ArtifactStore;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.domain.presentation.PresentationStore;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceReleaseRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TurnTimelineStore;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.storage.jdbc.config.JdbcStorageOptions;
import dev.horizen.agent.storage.redis.RedisAgentRuntimeStore;
import dev.horizen.agent.transaction.UnitOfWork;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.LeaseRenewalProperties;
import dev.horizen.agent.web.config.RuntimeStorageProperties;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import redis.clients.jedis.*;

import java.net.URI;

/**
 * 组装 Redis 状态、消息总线与连接池，供分布式执行和回放使用。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "horizen.agent.storage.mode", havingValue = "DISTRIBUTED")
public class RedisStorageConfiguration {
    /**
     * 计算或取得本方法声明的结果，供当前RedisStorageConfiguration处理步骤使用。
     *
     * @param storage 当前Redis存储组装持有的存储对象，供相应处理步骤使用。
     * @param leases  当前Redis存储组装持有的租约集合对象，供相应处理步骤使用。
     * @return 本次操作返回的JedisPooled结果。
     */
    @Bean(destroyMethod = "close", name = "agentRedisCommands")
    @Lazy
    JedisPooled commands(RuntimeStorageProperties storage, LeaseRenewalProperties leases) {
        GenericObjectPoolConfig<Connection> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(storage.getRedisMaximumPoolSize());
        pool.setMaxIdle(storage.getRedisMaximumPoolSize());
        pool.setMaxWait(leases.getConnectionAcquireTimeout());
        JedisPooled commands = new JedisPooled(pool, URI.create(storage.getRedisUrl()));
        try {
            commands.ping();
            return commands;
        } catch (RuntimeException error) {
            commands.close();
            throw error;
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param storage 当前Redis存储组装持有的存储对象，供相应处理步骤使用。
     * @param leases  当前Redis存储组装持有的租约集合对象，供相应处理步骤使用。
     * @return 本次操作返回的Jedis连接池结果。
     */
    @Bean(destroyMethod = "close", name = "agentRedisSubscriptions")
    @Lazy
    JedisPool subscriptions(RuntimeStorageProperties storage, LeaseRenewalProperties leases) {
        GenericObjectPoolConfig<Jedis> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(storage.getRedisMaximumPoolSize());
        pool.setMaxIdle(storage.getRedisMaximumPoolSize());
        pool.setMaxWait(leases.getConnectionAcquireTimeout());
        return new JedisPool(pool, URI.create(storage.getRedisUrl()));
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param storage       当前Redis存储组装持有的存储对象，供相应处理步骤使用。
     * @param commands      当前Redis存储组装持有的命令集合对象，供相应处理步骤使用。
     * @param subscriptions 当前Redis存储组装持有的订阅集合对象，供相应处理步骤使用。
     * @return 本次操作返回的RedisAgent运行时存储结果。
     */
    @Bean
    @Lazy
    RedisAgentRuntimeStore distributedStore(
            RuntimeStorageProperties storage,
            @Qualifier("agentRedisCommands") JedisPooled commands,
            @Qualifier("agentRedisSubscriptions") JedisPool subscriptions) {
        return new RedisAgentRuntimeStore(
                commands,
                subscriptions,
                storage.getRedisKeyPrefix(),
                storage.getEventTtl(),
                storage.getSessionStateTtl());
    }

    /**
     * 计算或取得本方法声明的结果，供当前RedisStorageConfiguration处理步骤使用。
     *
     * @param properties    宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param source        待解析或转换的来源对象。
     * @param commands      当前Redis存储组装持有的命令集合对象，供相应处理步骤使用。
     * @param subscriptions 当前Redis存储组装持有的订阅集合对象，供相应处理步骤使用。
     * @param distributed   当前Redis存储组装持有的分布式对象，供相应处理步骤使用。
     * @param turns         提供执行集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param approvals     审批存储或待处理审批集合，用于原执行的暂停与恢复。
     * @param asks          提供提问集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param artifacts     产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param presentations 需要持久化或展示的结构化呈现块集合。
     * @param timeline      提供时间线能力的依赖，具体实现由当前组件的组装方传入。
     * @param releases      提供发布集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param documents     提供文档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param pointers      提供指针集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param transactions  执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     * @param options       可供当前请求选择的选项或策略集合。
     * @return 本次操作返回的运行时存储结果。
     */
    @Bean(destroyMethod = "close")
    RuntimeStorage runtimeStorage(
            AgentProperties properties,
            @Qualifier("agentDataSource") HikariDataSource source,
            @Qualifier("agentRedisCommands") ObjectProvider<JedisPooled> commands,
            @Qualifier("agentRedisSubscriptions") ObjectProvider<JedisPool> subscriptions,
            ObjectProvider<RedisAgentRuntimeStore> distributed,
            SessionTurnStore turns,
            ApprovalStore approvals,
            AskUserStore asks,
            ArtifactStore artifacts,
            PresentationStore presentations,
            TurnTimelineStore timeline,
            SessionWorkspaceReleaseRepository releases,
            WorkspaceDocumentRepository documents,
            WorkspaceSnapshotPointerRepository pointers,
            @Qualifier("agentUnitOfWork") UnitOfWork transactions,
            JdbcStorageOptions options) {
        if (!properties.ready()) return null;
        return RuntimeStorage.managed(
                source,
                commands.getObject(),
                subscriptions.getObject(),
                turns,
                approvals,
                artifacts,
                asks,
                presentations,
                timeline,
                releases,
                documents,
                pointers,
                transactions,
                distributed.getObject(),
                options.getInstanceId());
    }
}
