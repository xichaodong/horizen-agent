package dev.horizen.agent.application.workspace;

import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;

import lombok.RequiredArgsConstructor;
import lombok.Value;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 从单个工作区发布版本的目录派生 ZIP 视图，使用相同的内容提供器。 */
@RequiredArgsConstructor
public final class WorkspaceArchiveService {
    /** 读取受管工作区文件与目录元数据的仓储。 */
    private final WorkspaceManagementService workspaces;

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param project 当前工作区归档服务使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param files 文件集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param prefix 当前工作区归档服务使用的前缀，供其处理与状态记录使用。
     * @return 本次操作返回的归档结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Archive export(
            long project,
            String agent,
            List<WorkspaceCatalogRepository.File> files,
            String prefix) {
        AgentReleaseManifest.safePath(prefix + "/placeholder");
        var selected =
                files.stream()
                        .filter(f -> f.getPath().startsWith(prefix + "/"))
                        .sorted(Comparator.comparing(WorkspaceCatalogRepository.File::getPath))
                        .toList();
        if (selected.isEmpty()
                || selected.size() > 500
                || selected.stream().mapToLong(WorkspaceCatalogRepository.File::getSize).sum()
                        > 20L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid archive directory or size");
        try (var output = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (var file : selected) {
                    var entry = new ZipEntry(file.getPath().substring(prefix.length() + 1));
                    entry.setTime(0);
                    zip.putNextEntry(entry);
                    zip.write(workspaces.read(file));
                    zip.closeEntry();
                }
            }
            byte[] bytes = output.toByteArray();
            String reference =
                    workspaces
                            .getContents()
                            .uploadDerived(
                                    new WorkspaceDocumentKey(
                                            "project:" + project,
                                            agent,
                                            "exports",
                                            prefix + ".zip"),
                                    bytes);
            return new Archive(
                    prefix, reference, WorkspaceManagementService.hash(bytes), bytes.length);
        } catch (IOException e) {
            throw new IllegalStateException("Workspace archive failed", e);
        }
    }

    /** 工作区导出归档及其描述信息。 */
    @Value
    public static class Archive {
        /** 当前资源路径，路径解释和合法范围由所属文件系统适配器限定。 */
        String path;

        /** 内容对象的持久引用，供后续读取实际字节。 */
        String reference;

        /** 内容的 SHA-256 摘要，参与制品完整性验证。 */
        String sha256;

        /** 当前内容或集合的大小，计量方式由所属资源协议定义。 */
        long size;
    }
}
