package dev.horizen.agent.storage.memory;

import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;

import lombok.RequiredArgsConstructor;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

/** 供开发和测试显式选用的提供器，云端宿主不能隐式选择此实现。 */
@RequiredArgsConstructor
public final class InMemoryWorkspaceContentRepository implements WorkspaceContentRepository {
    /** SHARED的固定取值，用于相应策略和边界判断。 */
    private static final LinkedHashMap<String, InMemoryWorkspaceContentRepository> SHARED =
            new LinkedHashMap<>(16, 0.75f, true);

    /**
     * 计算或取得本方法声明的结果，供当前InMemoryWorkspaceContentRepository处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次操作返回的输入侧记忆工作区正文仓储结果。
     */
    public static synchronized InMemoryWorkspaceContentRepository shared(String key) {
        var found = SHARED.get(key);
        if (found != null) return found;
        if (SHARED.size() >= 64) SHARED.remove(SHARED.keySet().iterator().next());
        var created = new InMemoryWorkspaceContentRepository();
        SHARED.put(key, created);
        return created;
    }

    /**
     * 计算或取得本方法声明的结果，供当前InMemoryWorkspaceContentRepository处理步骤使用。
     *
     * @param source 待解析或转换的来源对象。
     * @return 本次操作返回的输入侧记忆工作区正文仓储结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static InMemoryWorkspaceContentRepository shared(DataSource source) {
        try (var connection = source.getConnection()) {
            return shared(connection.getMetaData().getURL());
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    /** 保存工作区实际内容字节的对象存储访问端口。 */
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    /** 最大的字节数，用于容量或传输限制。 */
    private final long maximumBytes;

    /** 当前内容存储已经占用的字节数。 */
    private long used;

    /** 创建输入侧记忆工作区正文仓储，初始化该组件所需的状态、配置或依赖。 */
    public InMemoryWorkspaceContentRepository() {
        this(64L * 1024 * 1024);
    }

    /**
     * 上传输入侧记忆工作区正文仓储。
     * 处理数组时使用副本，避免直接共享原数组内容。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public synchronized String upload(WorkspaceDocumentKey key, byte[] content) {
        if (used + content.length > maximumBytes)
            throw new IllegalStateException("Test content capacity exceeded");
        String id = "memory-workspace-" + UUID.randomUUID();
        objects.put(id, content.clone());
        used += content.length;
        return id;
    }

    /**
     * 下载输入侧记忆工作区正文仓储。
     * 处理数组时使用副本，避免直接共享原数组内容。
     *
     * @param reference 当前输入侧记忆工作区正文仓储使用的引用，供其处理与状态记录使用。
     * @param maximumBytes 最大的字节数，用于容量或传输限制。
     * @return 本次处理取得或生成的内容字节。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public byte[] download(String reference, long maximumBytes) {
        byte[] bytes = objects.get(reference);
        if (bytes == null || bytes.length > maximumBytes)
            throw new IllegalStateException("Workspace object unavailable or oversized");
        return bytes.clone();
    }

    /**
     * 删除输入侧记忆工作区正文仓储。
     *
     * @param reference 当前输入侧记忆工作区正文仓储使用的引用，供其处理与状态记录使用。
     */
    @Override
    public synchronized void delete(String reference) {
        byte[] removed = objects.remove(reference);
        if (removed != null) used -= removed.length;
    }

    /**
     * 计算或取得本方法声明的结果，供当前InMemoryWorkspaceContentRepository处理步骤使用。
     *
     * @return 本次操作返回的整数结果。
     */
    public int objectCount() {
        return objects.size();
    }

    /**
     * 检查是否包含输入侧记忆工作区正文仓储。
     *
     * @param reference 当前输入侧记忆工作区正文仓储使用的引用，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean contains(String reference) {
        return objects.containsKey(reference);
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public synchronized void close() {
        objects.clear();
        used = 0;
    }
}
