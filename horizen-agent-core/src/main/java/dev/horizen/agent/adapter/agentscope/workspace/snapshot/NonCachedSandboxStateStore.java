package dev.horizen.agent.adapter.agentscope.workspace.snapshot;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 对话状态仍使用缓存，但缺少所有者信息的 Harness 沙箱状态槽不持久化。 */
public final class NonCachedSandboxStateStore implements AgentStateStore {
    /** 沙箱使用的固定标识或协议文本。 */
    private static final String SANDBOX = "_sandbox_state";

    /** 被包装的原始实现，由本组件补充隔离、观测或恢复行为。 */
    private final AgentStateStore delegate;

    /**
     * 创建非缓存沙箱工作状态存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param delegate 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    public NonCachedSandboxStateStore(AgentStateStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 保存非缓存沙箱工作状态存储。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     * @param key 当前对象的查找或写入键。
     * @param value 待校验、转换或保存的原始值。
     */
    @Override
    public void save(String owner, String session, String key, State value) {
        if (!SANDBOX.equals(key)) delegate.save(owner, session, key, value);
    }

    /**
     * 保存非缓存沙箱工作状态存储。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     * @param key 当前对象的查找或写入键。
     * @param values 本次批量处理的值集合。
     */
    @Override
    public void save(String owner, String session, String key, List<? extends State> values) {
        if (!SANDBOX.equals(key)) delegate.save(owner, session, key, values);
    }

    /**
     * 读取非缓存沙箱工作状态存储。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     * @param key 当前对象的查找或写入键。
     * @param type 当前操作使用的目标类型或类别。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public <T extends State> Optional<T> get(
            String owner, String session, String key, Class<T> type) {
        return SANDBOX.equals(key) ? Optional.empty() : delegate.get(owner, session, key, type);
    }

    /**
     * 读取列表。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     * @param key 当前对象的查找或写入键。
     * @param type 当前操作使用的目标类型或类别。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public <T extends State> List<T> getList(
            String owner, String session, String key, Class<T> type) {
        return SANDBOX.equals(key) ? List.of() : delegate.getList(owner, session, key, type);
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
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     * @param key 当前对象的查找或写入键。
     * @param type 当前操作使用的目标类型或类别。
     * @return 本次操作返回的带版本工作状态结果。
     */
    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String owner, String session, String key, Class<T> type) {
        return SANDBOX.equals(key)
                ? new VersionedState<>(null, 0)
                : delegate.getVersioned(owner, session, key, type);
    }

    /**
     * 保存条件版本。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     * @param key 当前对象的查找或写入键。
     * @param value 待校验、转换或保存的原始值。
     * @param expected 当前非缓存沙箱工作状态存储使用的预期，供其处理与状态记录使用。
     * @return 本次操作返回的长整型结果。
     */
    @Override
    public long saveIfVersion(
            String owner, String session, String key, State value, long expected) {
        return SANDBOX.equals(key)
                ? UNVERSIONED
                : delegate.saveIfVersion(owner, session, key, value, expected);
    }

    /**
     * 检查是否存在非缓存沙箱工作状态存储。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(String owner, String session) {
        return delegate.exists(owner, session);
    }

    /**
     * 删除非缓存沙箱工作状态存储。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     */
    @Override
    public void delete(String owner, String session) {
        delegate.delete(owner, session);
    }

    /**
     * 删除非缓存沙箱工作状态存储。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前非缓存沙箱工作状态存储使用的会话，供其处理与状态记录使用。
     * @param key 当前对象的查找或写入键。
     */
    @Override
    public void delete(String owner, String session, String key) {
        if (!SANDBOX.equals(key)) delegate.delete(owner, session, key);
    }

    /**
     * 查询列表中的会话标识集合。
     *
     * @param owner 当前非缓存沙箱工作状态存储使用的数据归属，供其处理与状态记录使用。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public Set<String> listSessionIds(String owner) {
        return delegate.listSessionIds(owner);
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public void close() {
        delegate.close();
    }
}
