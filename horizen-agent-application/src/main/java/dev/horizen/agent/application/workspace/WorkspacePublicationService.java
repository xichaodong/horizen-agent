package dev.horizen.agent.application.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.ReleaseManifestCanonicalizer;
import dev.horizen.agent.domain.workspace.release.ReleaseManifestCanonicalizer.ContentEntry;
import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;
import dev.horizen.agent.domain.workspace.release.WorkspacePublicationValidator;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 完整发布用例；对象传输先于短时目录事务执行。
 */
@RequiredArgsConstructor
public class WorkspacePublicationService {
    /**
     * 待发布工作区的草稿目录仓储。
     */
    private final WorkspaceManagementService drafts;

    /**
     * 当前资源目录或目录定位键，用于查找可用发布与工具。
     */
    private final WorkspaceCatalogRepository catalog;

    /**
     * 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    private final WorkspaceContentRepository contents;

    /**
     * 在发布或执行前核对目录、文件与协议约束的校验器。
     */
    private final WorkspacePublicationValidator validator;

    /**
     * 保存或取得工作区归档内容的存储端口。
     */
    private final WorkspaceArchiveService archives;

    /**
     * 计算或取得本方法声明的结果，供当前WorkspacePublicationService处理步骤使用。
     *
     * @param project 当前工作区发布服务使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    public Optional<WorkspaceCatalogRepository.Release> current(long project, String agent) {
        new AgentCatalogKey(project, agent);
        return catalog.current(project, agent);
    }

    /**
     * 查找工作区发布服务。
     *
     * @param project 当前工作区发布服务使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param id      目标对象的标识。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    public Optional<WorkspaceCatalogRepository.Release> find(long project, String agent, long id) {
        new AgentCatalogKey(project, agent);
        return catalog.release(project, agent, id);
    }

    /**
     * 查询列表中的工作区发布服务。
     *
     * @param project 当前工作区发布服务使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次处理得到的结果集合。
     */
    public List<WorkspaceCatalogRepository.Release> list(long project, String agent) {
        new AgentCatalogKey(project, agent);
        return catalog.releases(project, agent);
    }

    /**
     * 校验工作区内容与 Skill 目录，生成规范清单与制品，再提交完整发布目录记录。
     *
     * @param project  当前工作区发布服务使用的Project，供其处理与状态记录使用。
     * @param agent    当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param expected 当前工作区发布服务使用的预期，供其处理与状态记录使用。
     * @param notes    当前工作区发布服务使用的说明集合，供其处理与状态记录使用。
     * @param operator 当前工作区发布服务使用的操作符，供其处理与状态记录使用。
     * @return 本次操作返回的发布结果。
     * @throws ApplicationError         当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public WorkspaceCatalogRepository.Release publish(
            long project, String agent, long expected, String notes, String operator) {
        var current = drafts.draft(project, agent);
        if (current.getVersion() != expected) throw new WorkspaceManagementService.Conflict();
        if (current.getFiles().stream()
                .noneMatch(f -> f.getPath().equals("AGENTS.md") && f.getSize() > 0))
            throw new IllegalArgumentException("Publication requires nonempty AGENTS.md");
        if (notes.length() > 1000) throw new IllegalArgumentException("Release notes exceed limit");
        ObjectNode manifest = JsonUtils.newMapper().createObjectNode();
        manifest.put("format", "workspace-files-v1");
        manifest.put("projectId", project);
        manifest.put("agentKey", agent);
        ArrayNode assets = manifest.putArray("assets");
        for (var file :
                current.getFiles().stream()
                        .sorted(Comparator.comparing(WorkspaceCatalogRepository.File::getPath))
                        .toList()) {
            ObjectNode asset = assets.addObject();
            asset.put("path", file.getPath());
            asset.put("url", "workspace-object:" + file.getReference());
            asset.put("sha256", file.getChecksum());
            asset.put("size", file.getSize());
            asset.put("mediaType", file.getMediaType());
        }
        String hash =
                ReleaseManifestCanonicalizer.workspaceHash(
                        project,
                        agent,
                        current.getFiles().stream()
                                .map(
                                        file ->
                                                new ContentEntry(
                                                        file.getPath(),
                                                        file.getChecksum(),
                                                        file.getSize()))
                                .toList());
        manifest.put("releaseHash", hash);
        var roots = validator.validate(current.getFiles(), drafts::read);
        var prior = catalog.current(project, agent).orElse(null);
        manifest.put("releaseNo", prior == null ? 1 : prior.getReleaseNo() + 1);
        if (prior != null && prior.getReleaseHash().equals(hash))
            throw new ApplicationError(
                    ApplicationError.Code.CONFLICT, "Workspace content already published");
        ArrayNode exports = manifest.putArray("archives");
        for (String name : roots.stream().sorted().toList())
            exports.add(
                    JsonUtils.newMapper()
                            .valueToTree(
                                    archives.export(
                                            project, agent, current.getFiles(), "skills/" + name)));
        try {
            return catalog.publish(
                    project, agent, expected, manifest.toString(), hash, notes, operator);
        } catch (IllegalStateException conflict) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, conflict.getMessage());
        }
    }

    /**
     * 为发布中的资源生成归档关联，保留原内容与发布身份。
     *
     * @param project 当前工作区发布服务使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param id      目标对象的标识。
     * @return 本次操作返回的数组节点结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ArrayNode archiveLinks(long project, String agent, long id) {
        var stored =
                find(project, agent, id)
                        .orElseThrow(
                                () -> new NoSuchElementException("Workspace release not found"));
        try {
            JsonNode manifest = JsonUtils.readTree(stored.getManifestJson());
            ArrayNode result = JsonUtils.newMapper().createArrayNode();
            for (var archive : manifest.path("archives")) {
                ObjectNode link = (ObjectNode) archive.deepCopy();
                link.remove("reference");
                link.put(
                        "url",
                        contents.downloadUrl(archive.path("reference").asText())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Workspace provider does not support archive download links"))
                                .toString());
                result.add(link);
            }
            return result;
        } catch (IOException error) {
            throw new IllegalStateException("Invalid release archive metadata", error);
        }
    }
}
