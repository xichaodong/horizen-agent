package dev.horizen.agent.application.workspace;

import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocument;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.WorkspaceAuditRepository;

import lombok.RequiredArgsConstructor;
import lombok.Value;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 提供分页审计查询和大小受限的快照预览，所有操作均为只读。
 */
@RequiredArgsConstructor
public final class WorkspaceAuditService {
    /**
     * 按工作区归属读取修改操作与前后内容引用的审计仓储。
     */
    private final WorkspaceAuditRepository audit;

    /**
     * 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    private final WorkspaceContentRepository contents;

    /**
     * 按归属和作用域访问工作区文档的仓储。
     */
    private final WorkspaceDocumentRepository documents;

    /**
     * 构造工作区审计记录使用的归属键。
     *
     * @param project     当前工作区审计服务使用的Project，供其处理与状态记录使用。
     * @param agent       当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param path        需要读取、写入或校验的路径。
     * @param memoryOwner 当前工作区审计服务使用的记忆数据归属，供其处理与状态记录使用。
     * @return 本次操作返回的工作区文档键结果。
     * @throws SecurityException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private WorkspaceDocumentKey key(long project, String agent, String path, String memoryOwner) {
        new AgentCatalogKey(project, agent);
        if (memoryOwner != null && !memoryOwner.isBlank()) {
            if (!"MEMORY.md".equals(path) || !audit.ownsMemory(project, agent, memoryOwner))
                throw new SecurityException("Memory does not belong to this project and Agent");
            return new WorkspaceDocumentKey(memoryOwner, agent, "global", path);
        }
        AgentReleaseManifest.safePath(path);
        return new WorkspaceDocumentKey("project:" + project, agent, "draft", path);
    }

    /**
     * 分页读取工作区操作审计记录。
     *
     * @param project 当前工作区审计服务使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param path    需要读取、写入或校验的路径。
     * @param owner   当前工作区审计服务使用的数据归属，供其处理与状态记录使用。
     * @param before  当前工作区审计服务使用的处理前，供其处理与状态记录使用。
     * @param size    当前内容或集合的大小，计量方式由所属资源协议定义。
     * @return 本次操作返回的页结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Page list(long project, String agent, String path, String owner, long before, int size) {
        if (size < 1 || size > 100 || before < 0)
            throw new IllegalArgumentException("Invalid audit pagination");
        boolean allFiles = (path == null || path.isBlank()) && (owner == null || owner.isBlank());
        var rows =
                audit.list(
                        key(project, agent, allFiles ? "AGENTS.md" : path, owner),
                        allFiles,
                        before,
                        size + 1);
        boolean more = rows.size() > size;
        var page = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new Page(page, more ? page.get(page.size() - 1).getSequence() : null);
    }

    /**
     * 读取单次工作区操作的审计详情。
     *
     * @param project  当前工作区审计服务使用的Project，供其处理与状态记录使用。
     * @param agent    当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param path     需要读取、写入或校验的路径。
     * @param owner    当前工作区审计服务使用的数据归属，供其处理与状态记录使用。
     * @param sequence 当前记录在对应序列中的位置，用于排序或继续读取。
     * @return 本次操作返回的详情结果。
     */
    public Detail detail(long project, String agent, String path, String owner, long sequence) {
        var operation =
                audit.find(key(project, agent, path, owner), sequence)
                        .orElseThrow(
                                () ->
                                        new NoSuchElementException(
                                                "Workspace audit record not found"));
        return new Detail(
                operation,
                preview(
                        operation.getBeforeReference(),
                        operation.getBeforeChecksum(),
                        operation.getBeforeSize(),
                        operation.getBeforeVersion(),
                        operation.getMediaType()),
                preview(
                        operation.getAfterReference(),
                        operation.getAfterChecksum(),
                        operation.getAfterSize(),
                        "DELETE".equals(operation.getOperationType())
                                ? 0L
                                : operation.getAfterVersion(),
                        operation.getMediaType()));
    }

    /**
     * 读取与记忆修改有关的审计信息。
     *
     * @param project 当前工作区审计服务使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param owner   当前工作区审计服务使用的数据归属，供其处理与状态记录使用。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Optional<WorkspaceDocument> memory(long project, String agent, String owner) {
        if (owner == null || owner.isBlank())
            throw new IllegalArgumentException("Memory owner is required");
        return documents.find(key(project, agent, "MEMORY.md", owner));
    }

    /**
     * 构造操作前后内容的审计预览。
     *
     * @param ref       当前工作区审计服务使用的引用，供其处理与状态记录使用。
     * @param checksum  内容校验值，用于确认传输或存储后的内容一致。
     * @param size      当前内容或集合的大小，计量方式由所属资源协议定义。
     * @param version   记录版本，用于乐观并发控制或区分协议版本。
     * @param mediaType 当前工作区审计服务使用的媒体类型，供其处理与状态记录使用。
     * @return 本次操作返回的预览结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private Preview preview(
            String ref, String checksum, Long size, Long version, String mediaType) {
        if (ref == null)
            return new Preview(
                    version != null && version == 0 ? "" : null,
                    version != null && version == 0,
                    false,
                    false);
        boolean text =
                mediaType != null && (mediaType.startsWith("text/") || mediaType.contains("json"));
        if (!text) return new Preview(null, true, false, true);
        if (size == null || size > 10L * 1024 * 1024)
            throw new IllegalStateException("Invalid historical workspace size");
        byte[] bytes = contents.download(ref, 10L * 1024 * 1024);
        if (bytes.length != size || !WorkspaceManagementService.hash(bytes).equals(checksum))
            throw new IllegalStateException("Historical workspace integrity mismatch");
        int length = Math.min(bytes.length, 256 * 1024);
        return new Preview(
                new String(bytes, 0, length, StandardCharsets.UTF_8),
                true,
                length < bytes.length,
                false);
    }

    /**
     * 工作区审计的分页查询结果。
     */
    @Value
    public static class Page {
        /**
         * 条目集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         */
        List<WorkspaceAuditRepository.Operation> items;

        /**
         * 下一页查询使用的不透明游标；没有下一页时可为空。
         */
        Long nextCursor;
    }

    /**
     * 单次工作区操作的审计详情。
     */
    @Value
    public static class Detail {
        /**
         * 当前审计查询或提交所涉及的一次工作区操作。
         */
        WorkspaceAuditRepository.Operation operation;

        /**
         * 本次操作之前的内容或状态，用于差异与审计展示。
         */
        Preview before;

        /**
         * 本次操作提交后的内容或状态，用于差异与审计展示。
         */
        Preview after;
    }

    /**
     * 工作区修改内容的预览信息。
     */
    @Value
    public static class Preview {
        /**
         * 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
         */
        String content;

        /**
         * 可用的状态标记，用于选择当前组件的处理路径。
         */
        boolean available;

        /**
         * 已截断的状态标记，用于选择当前组件的处理路径。
         */
        boolean truncated;

        /**
         * 二进制的状态标记，用于选择当前组件的处理路径。
         */
        boolean binary;
    }
}
