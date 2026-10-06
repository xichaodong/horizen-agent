package dev.horizen.agent.application.workspace;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;

import java.util.*;

/**
 * Agent 项目工作区管理用例；所有文件内容均在 SQL 提交前完成暂存。
 */
public final class WorkspaceManagementService {
    /**
     * 当前资源目录或目录定位键，用于查找可用发布与工具。
     */
    @Getter(AccessLevel.PACKAGE)
    private final WorkspaceCatalogRepository catalog;

    /**
     * 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    @Getter(AccessLevel.PACKAGE)
    private final WorkspaceContentRepository contents;

    /**
     * 创建工作区管理服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param catalog  当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param contents 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    public WorkspaceManagementService(
            WorkspaceCatalogRepository catalog, WorkspaceContentRepository contents) {
        this.catalog = Objects.requireNonNull(catalog);
        this.contents = Objects.requireNonNull(contents);
    }

    /**
     * 计算或取得本方法声明的结果，供当前WorkspaceManagementService处理步骤使用。
     *
     * @param project 当前工作区管理服务使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次操作返回的草稿结果。
     */
    public WorkspaceCatalogRepository.Draft draft(long project, String agent) {
        coordinates(project, agent);
        return catalog.draft(project, agent);
    }

    /**
     * 在指定工作区归属与路径范围内读取内容及其当前版本。
     *
     * @param file 当前工作区管理服务持有的文件对象，供相应处理步骤使用。
     * @return 本次处理取得或生成的内容字节。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public byte[] read(WorkspaceCatalogRepository.File file) {
        byte[] bytes = contents.download(file.getReference(), 10L * 1024 * 1024);
        if (bytes.length != file.getSize() || !hash(bytes).equals(file.getChecksum()))
            throw new IllegalStateException("Workspace integrity mismatch");
        return bytes;
    }

    /**
     * 按预期版本提交工作区修改，冲突时不覆盖他人已经提交的内容。
     *
     * @param project    当前工作区管理服务使用的Project，供其处理与状态记录使用。
     * @param agent      当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param expected   当前工作区管理服务使用的预期，供其处理与状态记录使用。
     * @param skillsJson Skill集合的 JSON 表示，供持久化或协议转换使用。
     * @param requested  请求的的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param operator   当前工作区管理服务使用的操作符，供其处理与状态记录使用。
     * @return 本次操作返回的草稿结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public WorkspaceCatalogRepository.Draft save(
            long project,
            String agent,
            long expected,
            String skillsJson,
            List<Input> requested,
            String operator) {
        if (!"[]".equals(skillsJson))
            throw new IllegalArgumentException(
                    "Skills must be workspace files under skills/, not external version selections");
        return save(project, agent, expected, requested, operator);
    }

    /**
     * 按预期版本提交工作区修改，冲突时不覆盖他人已经提交的内容。
     *
     * @param project   当前工作区管理服务使用的Project，供其处理与状态记录使用。
     * @param agent     当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param expected  当前工作区管理服务使用的预期，供其处理与状态记录使用。
     * @param requested 请求的的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param operator  当前工作区管理服务使用的操作符，供其处理与状态记录使用。
     * @return 本次操作返回的草稿结果。
     * @throws Conflict                 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public WorkspaceCatalogRepository.Draft save(
            long project, String agent, long expected, List<Input> requested, String operator) {
        coordinates(project, agent);
        if (expected < 0 || requested.size() > 500)
            throw new IllegalArgumentException("Workspace limits exceeded");
        var old = catalog.draft(project, agent);
        if (old.getVersion() != expected) throw new Conflict();
        Map<String, WorkspaceCatalogRepository.File> prior = new HashMap<>();
        old.getFiles().forEach(f -> prior.put(f.getPath(), f));
        List<WorkspaceCatalogRepository.File> staged = new ArrayList<>();
        List<String> uploaded = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        long total = 0;
        try {
            for (var input : requested) {
                AgentReleaseManifest.safePath(input.getPath());
                if (!paths.add(input.getPath()))
                    throw new IllegalArgumentException("Duplicate workspace path");
                WorkspaceCatalogRepository.File file;
                if (input.getBytes() == null) {
                    file = prior.get(input.getPath());
                    if (file == null || !file.getChecksum().equals(input.getChecksum()))
                        throw new IllegalArgumentException("Untrusted workspace reference");
                } else {
                    byte[] bytes = input.getBytes();
                    long limit =
                            input.getPath().equals("AGENTS.md")
                                    || input.getPath().startsWith("subagents/")
                                    ? 65536
                                    : 10L * 1024 * 1024;
                    if (bytes.length > limit)
                        throw new IllegalArgumentException("Workspace file too large");
                    String checksum = hash(bytes);
                    var previous = prior.get(input.getPath());
                    if (previous != null && previous.getChecksum().equals(checksum))
                        file = previous;
                    else {
                        String reference =
                                contents.upload(
                                        new WorkspaceDocumentKey(
                                                "project:" + project,
                                                agent,
                                                "draft",
                                                input.getPath()),
                                        bytes);
                        uploaded.add(reference);
                        file =
                                new WorkspaceCatalogRepository.File(
                                        input.getPath(),
                                        reference,
                                        checksum,
                                        bytes.length,
                                        input.getMediaType() == null
                                                ? "application/octet-stream"
                                                : input.getMediaType());
                    }
                }
                total += file.getSize();
                if (total > 20L * 1024 * 1024)
                    throw new IllegalArgumentException("Workspace exceeds 20 MiB");
                staged.add(file);
            }
            for (String p : paths)
                for (String q : paths)
                    if (q.startsWith(p + "/"))
                        throw new IllegalArgumentException("Workspace path collision");
            if (!catalog.save(project, agent, expected, staged, operator)) throw new Conflict();
            // 旧对象可能被不可变发布版本引用，此处不能删除。
            return new WorkspaceCatalogRepository.Draft(expected + 1, List.copyOf(staged));
        } catch (RuntimeException e) {
            uploaded.forEach(
                    ref -> {
                        try {
                            contents.delete(ref);
                        } catch (RuntimeException ignored) {
                        }
                    });
            throw e;
        }
    }

    /**
     * 计算摘要工作区管理服务。
     *
     * @param bytes 当前操作处理的内容字节。
     * @return 本次处理生成或读取的文本。
     */
    public static String hash(byte[] bytes) {
        return DigestUtils.sha256Hex(bytes);
    }

    /**
     * 完成当前操作的coordinates步骤，按实现更新相应状态或依赖。
     *
     * @param project 当前工作区管理服务使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void coordinates(long project, String agent) {
        if (project <= 0) throw new IllegalArgumentException("Invalid project");
        new AgentCatalogKey(project, agent);
    }

    /**
     * 管理接口传入的工作区文件及元数据。
     */
    @Value
    public static class Input {
        /**
         * 当前资源路径，路径解释和合法范围由所属文件系统适配器限定。
         */
        String path;

        /**
         * 当前内容字节或字节计数，用于传输、校验与容量控制。
         */
        byte[] bytes;

        /**
         * 内容校验值，用于确认传输或存储后的内容一致。
         */
        String checksum;

        /**
         * 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。
         */
        String mediaType;
    }

    /**
     * 工作区管理服务内部的Conflict，封装该步骤需要的状态或输入输出。
     */
    public static final class Conflict extends RuntimeException {
        /**
         * 创建Conflict，初始化该组件所需的状态、配置或依赖。
         */
        public Conflict() {
            super("Workspace version conflict; reload before editing");
        }
    }
}
