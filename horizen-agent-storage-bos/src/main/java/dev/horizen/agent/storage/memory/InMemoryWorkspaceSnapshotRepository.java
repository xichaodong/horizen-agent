package dev.horizen.agent.storage.memory;

import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 供测试和开发显式选用的容量受限归档提供器。 */
public final class InMemoryWorkspaceSnapshotRepository implements WorkspaceSnapshotRepository {
    /** SHARED的固定取值，用于相应策略和边界判断。 */
    private static final Map<String, InMemoryWorkspaceSnapshotRepository> SHARED =
            new LinkedHashMap<>();

    /**
     * 计算或取得本方法声明的结果，供当前InMemoryWorkspaceSnapshotRepository处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次操作返回的输入侧记忆工作区快照仓储结果。
     */
    public static synchronized InMemoryWorkspaceSnapshotRepository shared(String key) {
        if (!SHARED.containsKey(key) && SHARED.size() >= 64)
            SHARED.remove(SHARED.keySet().iterator().next());
        return SHARED.computeIfAbsent(key, k -> new InMemoryWorkspaceSnapshotRepository());
    }

    /** 保存或取得工作区归档内容的存储端口。 */
    private final Map<String, byte[]> archives = new ConcurrentHashMap<>();

    /**
     * 上传输入侧记忆工作区快照仓储。
     *
     * @param id 目标对象的标识。
     * @param input 本次处理的输入。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public void upload(String id, InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(64 * 1024 * 1024 + 1);
        if (bytes.length > 64 * 1024 * 1024 || archives.size() >= 1000)
            throw new IOException("Test archive capacity exceeded");
        archives.put(id, bytes);
    }

    /**
     * 下载输入侧记忆工作区快照仓储。
     *
     * @param id 目标对象的标识。
     * @return 本次操作返回的输入事件流结果。
     * @throws FileNotFoundException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public InputStream download(String id) throws IOException {
        byte[] bytes = archives.get(id);
        if (bytes == null) throw new FileNotFoundException(id);
        return new ByteArrayInputStream(bytes);
    }

    /**
     * 检查是否存在输入侧记忆工作区快照仓储。
     *
     * @param id 目标对象的标识。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(String id) {
        return archives.containsKey(id);
    }

    /**
     * 删除输入侧记忆工作区快照仓储。
     *
     * @param id 目标对象的标识。
     */
    public void delete(String id) {
        archives.remove(id);
    }
}
