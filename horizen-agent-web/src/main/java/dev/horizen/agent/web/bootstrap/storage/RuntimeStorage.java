package dev.horizen.agent.web.bootstrap.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import dev.horizen.agent.domain.artifact.ArtifactStore;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.domain.presentation.PresentationStore;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceReleaseRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TurnTimelineStore;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcArtifactStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcPresentationStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcTurnTimelineStore;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcApprovalStore;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcAskUserStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionWorkspaceReleaseRepository;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcWorkspaceSnapshotPointerRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;
import dev.horizen.agent.storage.redis.RedisAgentRuntimeStore;
import dev.horizen.agent.transaction.UnitOfWork;
import dev.horizen.agent.web.config.RuntimeStorageProperties;

import io.agentscope.harness.agent.bus.MessageBus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import redis.clients.jedis.Connection;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Web 宿主持有的 MySQL 与 Redis 客户端集合。
 */
public final class RuntimeStorage implements AutoCloseable {

    /**
     * 当前对象是否拥有连接池的关闭权，避免释放调用方持有的资源。
     */
    private final boolean ownsPools;

    /**
     * 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    private final HikariDataSource dataSource;

    /**
     * 用于状态与事件读写的 Redis 命令客户端。
     */
    private final JedisPooled redisCommands;

    /**
     * 用于订阅或专用连接的 Redis 连接池。
     */
    private final JedisPool redisSubscriptions;

    /**
     * 负责会话占用、执行事实与正式消息的持久化端口。
     */
    @Getter
    private final SessionTurnStore sessionTurns;

    /**
     * 审批存储或待处理审批集合，用于原执行的暂停与恢复。
     */
    @Getter
    private final ApprovalStore approvals;

    /**
     * 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     */
    @Getter
    private final UnitOfWork transactions;

    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    @Getter
    private final ArtifactStore artifacts;

    /**
     * 澄清请求的存储或服务，用于回答处理与执行恢复。
     */
    @Getter
    private final AskUserStore askUsers;

    /**
     * 需要持久化或展示的结构化呈现块集合。
     */
    @Getter
    private final PresentationStore presentations;

    /**
     * 存储正式过程事件的时间线端口，供持久化与刷新恢复使用。
     */
    @Getter
    private final TurnTimelineStore timeline;

    /**
     * 保存原会话与完整发布绑定的仓储。
     */
    @Getter
    private final SessionWorkspaceReleaseRepository workspaceReleases;

    /**
     * 跨会话记忆与受管文本工作区的文档仓储。
     */
    @Getter
    private final WorkspaceDocumentRepository workspaceDocuments;

    /**
     * 保存会话最近工作区快照引用的仓储。
     */
    @Getter
    private final WorkspaceSnapshotPointerRepository snapshotPointers;

    /**
     * 共享的 Agent 工作状态存储，用于跨实例执行与恢复。
     */
    @Getter
    private final RedisAgentRuntimeStore distributedStore;

    /**
     * 当前服务实例标识，用于区分分布式执行与资源统计。
     */
    @Getter
    private final String instanceId;

    /**
     * 创建运行时存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource         当前存储适配器使用的数据源；资源所有权由组装方约定。
     * @param redisCommands      用于状态与事件读写的 Redis 命令客户端。
     * @param redisSubscriptions 用于订阅或专用连接的 Redis 连接池。
     * @param sessionTurns       提供会话执行集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param approvals          审批存储或待处理审批集合，用于原执行的暂停与恢复。
     * @param artifacts          产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param askUsers           澄清请求的存储或服务，用于回答处理与执行恢复。
     * @param presentations      需要持久化或展示的结构化呈现块集合。
     * @param timeline           提供时间线能力的依赖，具体实现由当前组件的组装方传入。
     * @param workspaceReleases  提供工作区发布集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param workspaceDocuments 提供工作区文档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param distributedStore   共享的 Agent 工作状态存储，用于跨实例执行与恢复。
     * @param instanceId         当前服务实例标识，用于区分分布式执行与资源统计。
     * @param pointers           提供指针集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param transactions       执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     * @param ownsPools          当前对象是否拥有连接池的关闭权，避免释放调用方持有的资源。
     */
    private RuntimeStorage(
            HikariDataSource dataSource,
            JedisPooled redisCommands,
            JedisPool redisSubscriptions,
            SessionTurnStore sessionTurns,
            ApprovalStore approvals,
            ArtifactStore artifacts,
            AskUserStore askUsers,
            PresentationStore presentations,
            TurnTimelineStore timeline,
            SessionWorkspaceReleaseRepository workspaceReleases,
            WorkspaceDocumentRepository workspaceDocuments,
            RedisAgentRuntimeStore distributedStore,
            String instanceId,
            WorkspaceSnapshotPointerRepository pointers,
            UnitOfWork transactions,
            boolean ownsPools) {
        this.ownsPools = ownsPools;
        this.dataSource = dataSource;
        this.redisCommands = redisCommands;
        this.redisSubscriptions = redisSubscriptions;
        this.sessionTurns = sessionTurns;
        this.approvals = approvals;
        this.transactions = transactions;
        this.artifacts = artifacts;
        this.askUsers = askUsers;
        this.presentations = presentations;
        this.timeline = timeline;
        this.workspaceReleases = workspaceReleases;
        this.workspaceDocuments = workspaceDocuments;
        this.snapshotPointers = pointers;
        this.distributedStore = distributedStore;
        this.instanceId = instanceId;
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeStorage处理步骤使用。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param contents   资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @return 本次操作返回的运行时存储结果。
     */
    public static RuntimeStorage open(
            RuntimeStorageProperties properties, WorkspaceContentRepository contents) {
        return open(properties, Duration.ofSeconds(3), Duration.ofSeconds(3), contents);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param properties               宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param leaseQueryTimeout        租约查询允许持续的最长等待时间。
     * @param connectionAcquireTimeout 连接取得租约允许持续的最长等待时间。
     * @param contents                 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @return 本次操作返回的运行时存储结果。
     */
    public static RuntimeStorage open(
            RuntimeStorageProperties properties,
            Duration leaseQueryTimeout,
            Duration connectionAcquireTimeout,
            WorkspaceContentRepository contents) {
        HikariConfig jdbc = new HikariConfig();
        jdbc.setJdbcUrl(properties.getJdbcUrl());
        if (!properties.getJdbcUsername().isEmpty()) {
            jdbc.setUsername(properties.getJdbcUsername());
        }
        if (!properties.getJdbcPassword().isEmpty()) {
            jdbc.setPassword(properties.getJdbcPassword());
        }
        jdbc.setMaximumPoolSize(properties.getJdbcMaximumPoolSize());
        jdbc.setConnectionTimeout(connectionAcquireTimeout.toMillis());
        jdbc.setPoolName("horizen-agent-jdbc");
        HikariDataSource dataSource = new HikariDataSource(jdbc);

        JedisPooled commands = null;
        JedisPool subscriptions = null;
        try {
            URI redisUri = URI.create(properties.getRedisUrl());
            GenericObjectPoolConfig<Connection> commandPool = new GenericObjectPoolConfig<>();
            commandPool.setMaxTotal(properties.getRedisMaximumPoolSize());
            commandPool.setMaxIdle(properties.getRedisMaximumPoolSize());
            commandPool.setMaxWait(connectionAcquireTimeout);
            GenericObjectPoolConfig<Jedis> subscriptionPool = new GenericObjectPoolConfig<>();
            subscriptionPool.setMaxTotal(properties.getRedisMaximumPoolSize());
            subscriptionPool.setMaxIdle(properties.getRedisMaximumPoolSize());
            subscriptionPool.setMaxWait(connectionAcquireTimeout);
            commands = new JedisPooled(commandPool, redisUri);
            subscriptions = new JedisPool(subscriptionPool, redisUri);
            commands.ping();
            RedisAgentRuntimeStore distributedStore =
                    new RedisAgentRuntimeStore(
                            commands,
                            subscriptions,
                            properties.getRedisKeyPrefix(),
                            properties.getEventTtl(),
                            properties.getSessionStateTtl());
            String instanceId =
                    properties.getInstanceId().isEmpty()
                            ? "agent-" + UUID.randomUUID()
                            : properties.getInstanceId();
            return new RuntimeStorage(
                    dataSource,
                    commands,
                    subscriptions,
                    new JdbcSessionTurnStore(dataSource, leaseQueryTimeout),
                    new JdbcApprovalStore(dataSource),
                    new JdbcArtifactStore(dataSource),
                    new JdbcAskUserStore(dataSource),
                    new JdbcPresentationStore(dataSource),
                    new JdbcTurnTimelineStore(dataSource),
                    new JdbcSessionWorkspaceReleaseRepository(dataSource),
                    new JdbcWorkspaceDocumentRepository(dataSource, contents),
                    distributedStore,
                    instanceId,
                    new JdbcWorkspaceSnapshotPointerRepository(dataSource, instanceId),
                    new JdbcUnitOfWork(dataSource),
                    true);
        } catch (RuntimeException error) {
            if (commands != null) {
                commands.close();
            }
            if (subscriptions != null) {
                subscriptions.close();
            }
            dataSource.close();
            throw error;
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param source        待解析或转换的来源对象。
     * @param commands      当前运行时存储持有的命令集合对象，供相应处理步骤使用。
     * @param subscriptions 当前运行时存储持有的订阅集合对象，供相应处理步骤使用。
     * @param turns         提供执行集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param approvals     审批存储或待处理审批集合，用于原执行的暂停与恢复。
     * @param artifacts     产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param asks          提供提问集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param presentations 需要持久化或展示的结构化呈现块集合。
     * @param timeline      提供时间线能力的依赖，具体实现由当前组件的组装方传入。
     * @param releases      提供发布集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param documents     提供文档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param pointers      提供指针集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param transactions  执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。
     * @param distributed   提供分布式能力的依赖，具体实现由当前组件的组装方传入。
     * @param instanceId    当前服务实例标识，用于区分分布式执行与资源统计。
     * @return 本次操作返回的运行时存储结果。
     */
    public static RuntimeStorage managed(
            HikariDataSource source,
            JedisPooled commands,
            JedisPool subscriptions,
            SessionTurnStore turns,
            ApprovalStore approvals,
            ArtifactStore artifacts,
            AskUserStore asks,
            PresentationStore presentations,
            TurnTimelineStore timeline,
            SessionWorkspaceReleaseRepository releases,
            WorkspaceDocumentRepository documents,
            WorkspaceSnapshotPointerRepository pointers,
            UnitOfWork transactions,
            RedisAgentRuntimeStore distributed,
            String instanceId) {
        return new RuntimeStorage(
                source,
                commands,
                subscriptions,
                turns,
                approvals,
                artifacts,
                asks,
                presentations,
                timeline,
                releases,
                documents,
                distributed,
                instanceId,
                pointers,
                transactions,
                false);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeStorage处理步骤使用。
     *
     * @return 本次操作返回的消息消息总线结果。
     */
    public MessageBus messageBus() {
        return distributedStore.messageBus();
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的数据库连接池状态结果。
     */
    public DatabasePoolStatus databasePoolStatus() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null) {
            return new DatabasePoolStatus(0, 0, 0, 0, dataSource.getMaximumPoolSize());
        }
        return new DatabasePoolStatus(
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection(),
                dataSource.getMaximumPoolSize());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的Redis连接池集合状态结果。
     */
    public RedisPoolsStatus redisPoolsStatus() {
        return new RedisPoolsStatus(
                new RedisPoolStatus(
                        redisCommands.getPool().getNumActive(),
                        redisCommands.getPool().getNumIdle(),
                        redisCommands.getPool().getNumWaiters(),
                        redisCommands.getPool().getMaxTotal()),
                new RedisPoolStatus(
                        redisSubscriptions.getNumActive(), redisSubscriptions.getNumIdle(),
                        redisSubscriptions.getNumWaiters(), redisSubscriptions.getMaxTotal()));
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     */
    @Override
    public void close() {
        if (!ownsPools) return;
        redisSubscriptions.close();
        redisCommands.close();
        dataSource.close();
    }

    /**
     * JDBC 连接池当前活跃、空闲、等待与容量统计。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatabasePoolStatus {
        /**
         * 当前仍处于观察状态的 SSE 连接数。
         */
        private int activeConnections;

        /**
         * 连接池中当前空闲可复用的连接数量。
         */
        private int idleConnections;

        /**
         * 自本实例启动以来接收的 SSE 连接总数。
         */
        private int totalConnections;

        /**
         * 当前等待从连接池取得连接的线程数量。
         */
        private int threadsAwaitingConnection;

        /**
         * 该连接池配置的最大连接数量。
         */
        private int maximumPoolSize;
    }

    /**
     * Redis 命令池和订阅池的运行资源快照。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedisPoolsStatus {
        /**
         * 当前组件用于普通状态读写的 Redis 命令客户端。
         */
        private RedisPoolStatus commands;

        /**
         * 当前组件用于实时订阅的专用连接或连接池。
         */
        private RedisPoolStatus subscriptions;
    }

    /**
     * 单个 Redis 连接池当前连接与等待统计。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedisPoolStatus {
        /**
         * 当前仍处于观察状态的 SSE 连接数。
         */
        private int activeConnections;

        /**
         * 连接池中当前空闲可复用的连接数量。
         */
        private int idleConnections;

        /**
         * 当前等待从连接池取得连接的线程数量。
         */
        private int threadsAwaitingConnection;

        /**
         * 该连接池配置的最大连接数量。
         */
        private int maximumPoolSize;
    }
}
