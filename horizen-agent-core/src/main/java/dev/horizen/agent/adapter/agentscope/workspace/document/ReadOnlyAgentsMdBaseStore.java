package dev.horizen.agent.adapter.agentscope.workspace.document;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent Workspace 使用的存储视图：允许读取 AGENTS.md，拒绝 Agent 写入或删除。
 */
public final class ReadOnlyAgentsMdBaseStore implements BaseStore {
    /**
     * 根执行段使用的固定标识或协议文本。
     */
    private static final String ROOT_SEGMENT = "root";

    /**
     * Agent集合Markdown使用的固定标识或协议文本。
     */
    private static final String AGENTS_MD = "AGENTS.md";

    /**
     * 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    private final BaseStore delegate;

    /**
     * 创建读取只读Agent集合Markdown基础存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param delegate 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    public ReadOnlyAgentsMdBaseStore(BaseStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 读取读取只读Agent集合Markdown基础存储。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     * @return 本次操作返回的存储条目结果。
     */
    @Override
    public StoreItem get(List<String> namespace, String key) {
        return delegate.get(namespace, key);
    }

    /**
     * 写入读取只读Agent集合Markdown基础存储。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     * @param value     待校验、转换或保存的原始值。
     */
    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        requireWritable(namespace, key);
        delegate.put(namespace, key, value);
    }

    /**
     * 写入条件版本。
     *
     * @param namespace       命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key             当前对象的查找或写入键。
     * @param value           待校验、转换或保存的原始值。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        if (isAgentsMd(namespace, key)) {
            return false;
        }
        return delegate.putIfVersion(namespace, key, value, expectedVersion);
    }

    /**
     * 计算或取得本方法声明的结果，供当前ReadOnlyAgentsMdBaseStore处理步骤使用。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param limit     本次处理或返回数量上限。
     * @param offset    本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        return delegate.search(namespace, limit, offset);
    }

    /**
     * 删除读取只读Agent集合Markdown基础存储。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     */
    @Override
    public void delete(List<String> namespace, String key) {
        requireWritable(namespace, key);
        delegate.delete(namespace, key);
    }

    /**
     * 取得并校验可写。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     * @throws UnsupportedOperationException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void requireWritable(List<String> namespace, String key) {
        if (isAgentsMd(namespace, key)) {
            throw new UnsupportedOperationException("AGENTS.md is read-only in the Agent runtime");
        }
    }

    /**
     * 判断Agent集合Markdown。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean isAgentsMd(List<String> namespace, String key) {
        if (namespace == null || namespace.isEmpty() || key == null) {
            return false;
        }
        String normalized = key.replace('\\', '/');
        while (normalized.startsWith("./") || normalized.startsWith("/")) {
            normalized =
                    normalized.startsWith("./") ? normalized.substring(2) : normalized.substring(1);
        }
        return ROOT_SEGMENT.equals(namespace.get(namespace.size() - 1))
                && AGENTS_MD.equals(normalized);
    }
}
