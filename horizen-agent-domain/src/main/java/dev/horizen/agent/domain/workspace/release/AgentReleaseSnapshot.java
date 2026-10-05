package dev.horizen.agent.domain.workspace.release;

import dev.horizen.agent.skill.SkillReleaseSnapshot;

import lombok.Getter;

import java.io.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单次执行的不可变内容视图；存储位置和传输细节由 Reader 隔离。 */
public final class AgentReleaseSnapshot implements AutoCloseable {
    /** 发布快照内容的读取端口，避免把缓存目录或资源所有权暴露给调用方。 */
    @FunctionalInterface
    public interface Reader {
        /**
         * 计算或取得本方法声明的结果，供当前Reader处理步骤使用。
         *
         * @param path 需要读取、写入或校验的路径。
         * @return 本次操作返回的输入事件流结果。
         */
        InputStream open(String path) throws IOException;
    }

    /** 已验证发布的描述清单，保存版本与制品完整性信息。 */
    @Getter private final AgentReleaseManifest manifest;

    /** 当前发布中可渐进读取的 Skill 正文与资源快照。 */
    @Getter private final SkillReleaseSnapshot skills;

    /** 在发布租约有效期间读取不可变内容的访问端口。 */
    private final Reader reader;

    /** 使用结束时归还当前快照租约的回调。 */
    private final Runnable release;

    /** 组件是否已关闭，用于避免重复释放或继续接收新工作。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建Agent发布快照，初始化该组件所需的状态、配置或依赖。
     *
     * @param manifest 当前Agent发布快照持有的清单对象，供相应处理步骤使用。
     * @param skills 当前Agent发布快照持有的Skill集合对象，供相应处理步骤使用。
     * @param reader 当前Agent发布快照持有的读取器对象，供相应处理步骤使用。
     * @param release 当前Agent发布快照持有的发布对象，供相应处理步骤使用。
     */
    public AgentReleaseSnapshot(
            AgentReleaseManifest manifest,
            SkillReleaseSnapshot skills,
            Reader reader,
            Runnable release) {
        this.manifest = Objects.requireNonNull(manifest);
        this.skills = Objects.requireNonNull(skills);
        this.reader = Objects.requireNonNull(reader);
        this.release = Objects.requireNonNull(release);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentReleaseSnapshot处理步骤使用。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的输入事件流结果。
     * @throws FileNotFoundException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public InputStream open(String path) throws IOException {
        if (closed.get()) throw new IllegalStateException("Publication lease is closed");
        AgentReleaseManifest.safePath(path);
        if (manifest.getAssets().stream().noneMatch(a -> a.getPath().equals(path)))
            throw new FileNotFoundException(path);
        return reader.open(path);
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     * 并发状态更新包含比较交换操作。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }
}
