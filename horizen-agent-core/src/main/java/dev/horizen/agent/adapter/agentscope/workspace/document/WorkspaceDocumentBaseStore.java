package dev.horizen.agent.adapter.agentscope.workspace.document;

import dev.horizen.agent.domain.workspace.document.WorkspaceDocument;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 云端小文档的 AgentScope BaseStore 适配器。
 *
 * <p>仅接受按所有者隔离的 {@code MEMORY.md} 路径。审计历史以结构化数据保存，不作为文件暴露。
 * 条件写入保留精确编辑的 CAS 语义；文档存在后拒绝无条件上传，
 * 需要追加时应使用工作区应用服务的幂等追加操作。
 */
public final class WorkspaceDocumentBaseStore implements BaseStore {
    /**
     * Agent集合使用的固定标识或协议文本。
     */
    private static final String AGENTS = "agents";

    /**
     * 使用者使用的固定标识或协议文本。
     */
    private static final String USERS = "users";

    /**
     * 根使用的固定标识或协议文本。
     */
    private static final String ROOT = "root";

    /**
     * 记忆使用的固定标识或协议文本。
     */
    private static final String MEMORY = "memory";

    /**
     * 记忆Markdown使用的固定标识或协议文本。
     */
    private static final String MEMORY_MD = "MEMORY.md";

    /**
     * GLOBAL作用域使用的固定标识或协议文本。
     */
    private static final String GLOBAL_SCOPE = "global";

    /**
     * 按归属和作用域访问工作区文档的仓储。
     */
    private final WorkspaceDocumentRepository documents;

    /**
     * 创建工作区文档基础存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param documents 提供文档集合能力的依赖，具体实现由当前组件的组装方传入。
     */
    public WorkspaceDocumentBaseStore(WorkspaceDocumentRepository documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /**
     * 读取工作区文档基础存储。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     * @return 本次操作返回的存储条目结果。
     */
    @Override
    public StoreItem get(List<String> namespace, String key) {
        WorkspaceDocumentKey documentKey = key(namespace, key);
        return documents.find(documentKey).map(this::toStoreItem).orElse(null);
    }

    /**
     * 根据文档路由策略提交可写工作区内容，不允许通过此视图改写只读发布文件。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     * @param value     待校验、转换或保存的原始值。
     * @throws UnsupportedOperationException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        WorkspaceDocumentKey documentKey = key(namespace, key);
        String content = requireUtf8Content(value);
        if (!documents.createIfAbsent(documentKey, content)) {
            throw new UnsupportedOperationException(
                    "Unconditional workspace upload is not supported for existing memory documents; "
                            + "use a versioned edit or idempotent append");
        }
    }

    /**
     * 在原版本仍匹配时提交文档内容，冲突时不覆盖新版本。
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
        WorkspaceDocumentKey documentKey = key(namespace, key);
        String content = requireUtf8Content(value);
        return expectedVersion == 0
                ? documents.createIfAbsent(documentKey, content)
                : documents.replace(documentKey, content, expectedVersion);
    }

    /**
     * 在允许的文档范围内检索工作区内容。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param limit     本次处理或返回数量上限。
     * @param offset    本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        Route route = route(namespace);
        if (limit <= 0 || offset < 0) return List.of();
        String prefix = MEMORY_MD;
        List<WorkspaceDocument> values =
                documents.list(
                        route.getOwnerKey(),
                        route.getAgentKey(),
                        GLOBAL_SCOPE,
                        prefix,
                        limit,
                        offset);
        List<StoreItem> items = new ArrayList<>(values.size());
        for (WorkspaceDocument value : values) {
            String key = value.getKey().getDocumentPath();
            items.add(new StoreItem(key, toStoreValue(value), value.getVersion()));
        }
        return items;
    }

    /**
     * 删除工作区文档基础存储。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     * @throws UnsupportedOperationException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void delete(List<String> namespace, String key) {
        throw new UnsupportedOperationException(
                "Deleting a memory document requires an explicit versioned workspace operation");
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param key       当前对象的查找或写入键。
     * @return 本次操作返回的工作区文档键结果。
     */
    private WorkspaceDocumentKey key(List<String> namespace, String key) {
        Route route = route(namespace);
        String normalized = normalizeKey(key);
        if (ROOT.equals(route.getSegment())) {
            if (!MEMORY_MD.equals(normalized)) throw unsupportedPath(normalized);
            return new WorkspaceDocumentKey(
                    route.getOwnerKey(), route.getAgentKey(), GLOBAL_SCOPE, normalized);
        }
        throw unsupportedPath(normalized);
    }

    /**
     * 按文件类型与路径选择工作区文档访问路由。
     *
     * @param namespace 命名空间的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次操作返回的路由结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static Route route(List<String> namespace) {
        if (namespace == null
                || namespace.size() != 5
                || !AGENTS.equals(namespace.get(0))
                || !USERS.equals(namespace.get(2))) {
            throw new IllegalArgumentException("Unexpected cloud workspace namespace");
        }
        String agentKey = namespace.get(1);
        String ownerKey = namespace.get(3);
        String segment = namespace.get(4);
        if (agentKey == null
                || agentKey.isBlank()
                || ownerKey == null
                || ownerKey.isBlank()
                || !ROOT.equals(segment)) {
            throw new IllegalArgumentException("Unexpected cloud workspace namespace");
        }
        return new Route(ownerKey, agentKey, segment);
    }

    /**
     * 校验当前工作区文本内容是否可以按 UTF-8 文档处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireUtf8Content(Map<String, Object> value) {
        if (value == null || !(value.get("content") instanceof String content)) {
            throw new IllegalArgumentException("workspace document content must be UTF-8 text");
        }
        Object encoding = value.get("encoding");
        if (encoding != null && !"utf-8".equalsIgnoreCase(String.valueOf(encoding))) {
            throw new IllegalArgumentException("memory documents only support UTF-8 text");
        }
        return content;
    }

    /**
     * 规范化键。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid workspace document path");
        }
        String normalized = key.replace('\\', '/').strip();
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.contains("..") || normalized.isBlank()) {
            throw new IllegalArgumentException("Invalid workspace document path");
        }
        return normalized;
    }

    /**
     * 转换为存储条目。
     *
     * @param document 当前工作区文档基础存储持有的文档对象，供相应处理步骤使用。
     * @return 本次操作返回的存储条目结果。
     */
    private StoreItem toStoreItem(WorkspaceDocument document) {
        String key = document.getKey().getDocumentPath();
        return new StoreItem(key, toStoreValue(document), document.getVersion());
    }

    /**
     * 转换为存储值。
     *
     * @param document 当前工作区文档基础存储持有的文档对象，供相应处理步骤使用。
     * @return 按返回类型约定组织的结果映射。
     */
    private static Map<String, Object> toStoreValue(WorkspaceDocument document) {
        return Map.of(
                "content", document.getContent(),
                "encoding", "utf-8",
                "created_at", document.getCreatedAt().toString(),
                "modified_at", document.getUpdatedAt().toString());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的Unsupported操作异常结果。
     */
    private static UnsupportedOperationException unsupportedPath(String path) {
        return new UnsupportedOperationException("Unsupported cloud memory path: " + path);
    }

    /**
     * 工作区文档基础存储内部的路由，封装该步骤需要的状态或输入输出。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Route {
        /**
         * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
         */
        @Getter(AccessLevel.PACKAGE)
        private final String ownerKey;

        /**
         * 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
         */
        @Getter(AccessLevel.PACKAGE)
        private final String agentKey;

        /**
         * 当前路径路由中的作用域片段，用于区分文档与发布内容。
         */
        @Getter(AccessLevel.PACKAGE)
        private final String segment;
    }
}
