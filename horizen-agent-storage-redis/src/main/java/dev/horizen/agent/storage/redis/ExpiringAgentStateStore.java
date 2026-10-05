package dev.horizen.agent.storage.redis;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

import redis.clients.jedis.UnifiedJedis;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 为 AgentScope 会话状态槽拥有的每个物理键添加空闲过期时间。 */
final class ExpiringAgentStateStore implements AgentStateStore {
    /** 列表SUFFIX使用的固定标识或协议文本。 */
    private static final String LIST_SUFFIX = ":list";

    /** 列表哈希SUFFIX使用的固定标识或协议文本。 */
    private static final String LIST_HASH_SUFFIX = ":_hash";

    /** 版本SUFFIX使用的固定标识或协议文本。 */
    private static final String VERSION_SUFFIX = ":ver";

    /** 键SUFFIX使用的固定标识或协议文本。 */
    private static final String KEYS_SUFFIX = ":_keys";

    /** 被包装的原始实现，由本组件补充隔离、观测或恢复行为。 */
    private final AgentStateStore delegate;

    /** 当前组件用于普通状态读写的 Redis 命令客户端。 */
    private final UnifiedJedis commands;

    /** 存储键前缀，用于区分本应用的数据与其他使用方。 */
    private final String keyPrefix;

    /** 保留时间，单位为秒。 */
    private final long ttlSeconds;

    /**
     * 创建过期Agent工作状态存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param delegate 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     * @param commands 当前过期Agent工作状态存储持有的命令集合对象，供相应处理步骤使用。
     * @param keyPrefix 存储键前缀，用于区分本应用的数据与其他使用方。
     * @param ttl 保留时间的时间配置，供等待、调度或失效判断使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    ExpiringAgentStateStore(
            AgentStateStore delegate, UnifiedJedis commands, String keyPrefix, Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.commands = Objects.requireNonNull(commands, "commands");
        String normalized =
                keyPrefix == null || keyPrefix.isBlank() ? "horizen-agent:" : keyPrefix.trim();
        this.keyPrefix = (normalized.endsWith(":") ? normalized : normalized + ":") + "session:";
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("session state ttl must be positive");
        }
        this.ttlSeconds = Math.max(1L, ttl.getSeconds());
    }

    /**
     * 保存过期Agent工作状态存储。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key 当前对象的查找或写入键。
     * @param value 待校验、转换或保存的原始值。
     */
    @Override
    public void save(String userId, String sessionId, String key, State value) {
        delegate.save(userId, sessionId, key, value);
        expireSession(userId, sessionId);
    }

    /**
     * 判断是否支持Versioning。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    /**
     * 读取带版本。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key 当前对象的查找或写入键。
     * @param type 当前操作使用的目标类型或类别。
     * @return 本次操作返回的带版本工作状态结果。
     */
    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getVersioned(userId, sessionId, key, type);
    }

    /**
     * 保存条件版本。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key 当前对象的查找或写入键。
     * @param value 待校验、转换或保存的原始值。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @return 本次操作返回的长整型结果。
     */
    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        long saved = delegate.saveIfVersion(userId, sessionId, key, value, expectedVersion);
        if (saved != UNVERSIONED) {
            expireSession(userId, sessionId);
        }
        return saved;
    }

    /**
     * 保存过期Agent工作状态存储。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key 当前对象的查找或写入键。
     * @param values 本次批量处理的值集合。
     */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        delegate.save(userId, sessionId, key, values);
        expireSession(userId, sessionId);
    }

    /**
     * 读取过期Agent工作状态存储。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key 当前对象的查找或写入键。
     * @param type 当前操作使用的目标类型或类别。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, sessionId, key, type);
    }

    /**
     * 读取列表。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key 当前对象的查找或写入键。
     * @param itemType 目标 Java 类型，用于类型化解码或对象转换。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        return delegate.getList(userId, sessionId, key, itemType);
    }

    /**
     * 检查是否存在过期Agent工作状态存储。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    /**
     * 删除过期Agent工作状态存储。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    /**
     * 删除过期Agent工作状态存储。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key 当前对象的查找或写入键。
     */
    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, sessionId, key);
        expireSession(userId, sessionId);
    }

    /**
     * 查询列表中的会话标识集合。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 完成当前操作的expireSession步骤，按实现更新相应状态或依赖。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private void expireSession(String userId, String sessionId) {
        String slot = keyPrefix + normalizeUser(userId) + "/" + sessionId;
        String keysKey = slot + KEYS_SUFFIX;
        Set<String> tracked = commands.smembers(keysKey);
        Set<String> physicalKeys = new LinkedHashSet<>();
        physicalKeys.add(keysKey);
        for (String trackedKey : tracked) {
            String physical = slot + ":" + trackedKey;
            physicalKeys.add(physical);
            if (trackedKey.endsWith(LIST_SUFFIX)) {
                physicalKeys.add(physical + LIST_HASH_SUFFIX);
            } else {
                physicalKeys.add(physical + VERSION_SUFFIX);
            }
        }
        for (String physicalKey : physicalKeys) {
            commands.expire(physicalKey, ttlSeconds);
        }
    }

    /**
     * 规范化用户。
     *
     * @param userId 上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @return 本次处理生成或读取的文本。
     */
    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? "__anon__" : userId;
    }
}
