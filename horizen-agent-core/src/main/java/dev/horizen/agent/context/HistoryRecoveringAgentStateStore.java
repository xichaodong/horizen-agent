package dev.horizen.agent.context;

import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.turn.SessionTurnStore;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标记 Redis {@code agent_state} 已确认缺失的情况，不将存储故障误判为缺失。AgentScope
 * 创建配置正确的全新状态后，由 {@link HistoryContextRecoveryMiddleware} 消费此标记。
 */
public final class HistoryRecoveringAgentStateStore implements AgentStateStore {
    /**
     * Agent工作状态使用的固定标识或协议文本。
     */
    private static final String AGENT_STATE = "agent_state";

    /**
     * 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    private final AgentStateStore delegate;

    /**
     * 负责history对应持久化访问的仓储依赖；调用方通过端口隔离具体存储实现。
     */
    private final SessionTurnStore history;

    /**
     * 缺失的去重集合，供成员查找或范围检查使用。
     */
    private final Set<Slot> missing = ConcurrentHashMap.newKeySet();

    /**
     * 创建历史恢复Agent工作状态存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param delegate 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     * @param history  提供历史能力的依赖，具体实现由当前组件的组装方传入。
     */
    public HistoryRecoveringAgentStateStore(AgentStateStore delegate, SessionTurnStore history) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.history = Objects.requireNonNull(history, "history");
    }

    /**
     * 读取带版本。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key       当前对象的查找或写入键。
     * @param type      当前操作使用的目标类型或类别。
     * @return 本次操作返回的带版本工作状态结果。
     */
    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        VersionedState<T> value = delegate.getVersioned(userId, sessionId, key, type);
        markIfMissing(userId, sessionId, key, type, value.isPresent());
        return value;
    }

    /**
     * 读取历史恢复Agent工作状态存储。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key       当前对象的查找或写入键。
     * @param type      当前操作使用的目标类型或类别。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        Optional<T> value = delegate.get(userId, sessionId, key, type);
        markIfMissing(userId, sessionId, key, type, value.isPresent());
        if ("toolkit_activeGroups".equals(key) && value.isPresent()) {
            missing.remove(new Slot(userId, sessionId));
        }
        return value;
    }

    /**
     * 本进程内同一次状态缺失仅返回一次持久化展示历史。
     */
    public List<ConversationMessage> consumeMissingHistory(String ownerKey, String sessionId) {
        if (!missing.remove(new Slot(ownerKey, sessionId))) return List.of();
        return history.listFinalMessages(ownerKey, sessionId);
    }

    /**
     * 完成当前操作的discardMissingHistory步骤，按实现更新相应状态或依赖。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    public void discardMissingHistory(String ownerKey, String sessionId) {
        missing.remove(new Slot(ownerKey, sessionId));
    }

    /**
     * 完成当前操作的markIfMissing步骤，按实现更新相应状态或依赖。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key       当前对象的查找或写入键。
     * @param type      当前操作使用的目标类型或类别。
     * @param present   存在的状态标记，用于选择当前组件的处理路径。
     */
    private <T extends State> void markIfMissing(
            String userId, String sessionId, String key, Class<T> type, boolean present) {
        if (AGENT_STATE.equals(key) && type == AgentState.class) {
            Slot slot = new Slot(userId, sessionId);
            if (present) {
                missing.remove(slot);
            } else {
                missing.add(slot);
            }
        }
    }

    /**
     * 保存历史恢复Agent工作状态存储。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key       当前对象的查找或写入键。
     * @param value     待校验、转换或保存的原始值。
     */
    @Override
    public void save(String userId, String sessionId, String key, State value) {
        delegate.save(userId, sessionId, key, value);
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
     * 保存条件版本。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key       当前对象的查找或写入键。
     * @param value     待校验、转换或保存的原始值。
     * @param version   记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次操作返回的长整型结果。
     */
    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long version) {
        return delegate.saveIfVersion(userId, sessionId, key, value, version);
    }

    /**
     * 保存历史恢复Agent工作状态存储。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key       当前对象的查找或写入键。
     * @param values    本次批量处理的值集合。
     */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        delegate.save(userId, sessionId, key, values);
    }

    /**
     * 读取列表。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key       当前对象的查找或写入键。
     * @param type      当前操作使用的目标类型或类别。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        List<T> values = delegate.getList(userId, sessionId, key, type);
        if ("memory_messages".equals(key) && !values.isEmpty()) {
            missing.remove(new Slot(userId, sessionId));
        }
        return values;
    }

    /**
     * 检查是否存在历史恢复Agent工作状态存储。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    /**
     * 删除历史恢复Agent工作状态存储。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    /**
     * 删除历史恢复Agent工作状态存储。
     *
     * @param userId    上游协议中的使用者标识；实际隔离含义由宿主传入的上下文约定。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param key       当前对象的查找或写入键。
     */
    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, sessionId, key);
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

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     */
    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 历史恢复Agent工作状态存储内部的状态槽，封装该步骤需要的状态或输入输出。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Slot {
        /**
         * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
         */
        private final String ownerKey;

        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        private final String sessionId;

        /**
         * 根据当前对象的值比较相等性。
         *
         * @param other 参与比较或合并的另一个对象。
         * @return 本次检查是否通过或本次更新是否成功。
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof Slot value
                    && Objects.equals(ownerKey, value.ownerKey)
                    && Objects.equals(sessionId, value.sessionId);
        }

        /**
         * 根据当前对象的值计算哈希值，保持与相等性判断一致。
         *
         * @return 本次操作返回的整数结果。
         */
        @Override
        public int hashCode() {
            return Objects.hash(ownerKey, sessionId);
        }
    }
}
