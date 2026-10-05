package dev.horizen.agent.web.api.workspace;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.workspace.WorkspaceAuditService;
import dev.horizen.agent.application.workspace.WorkspaceManagementService;
import dev.horizen.agent.application.workspace.WorkspacePublicationService;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;
import dev.horizen.agent.web.api.AgentApiMapper;
import dev.horizen.agent.web.api.ApiException;
import dev.horizen.agent.web.config.WorkspaceManagementProperties;
import dev.horizen.agent.web.identity.ServiceTokenVerifier;

import lombok.Setter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 仅供服务使用的管理接口；浏览器身份和项目访问权限由 Server 检查。 */
@RestController
@RequestMapping("/api/internal/workspaces")
@ConditionalOnProperty(name = "horizen.agent.workspace-management.enabled", havingValue = "true")
public class WorkspaceManagementController {
    /** 本组件调用的 {@code WorkspaceManagementService} 依赖，负责 service 对应的处理步骤。 */
    private final WorkspaceManagementService service;

    /** 完整工作区发布服务，提供会话绑定版本的准备与状态。 */
    private final WorkspacePublicationService publications;

    /** 宿主绑定的配置对象，供组件组装与策略校验使用。 */
    private final WorkspaceManagementProperties properties;

    /** 当前远端协议的 JSON 编解码器。 */
    private final ObjectMapper json;

    /** 按工作区归属读取修改操作与前后内容引用的审计仓储。 */
    @Setter(onMethod_ = @Autowired)
    private WorkspaceAuditService audit;

    /**
     * 创建工作区管理接口控制器，初始化该组件所需的状态、配置或依赖。
     *
     * @param service 提供服务能力的依赖，具体实现由当前组件的组装方传入。
     * @param publications 提供发布集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param json 提供JSON能力的依赖，具体实现由当前组件的组装方传入。
     */
    @Autowired
    public WorkspaceManagementController(
            WorkspaceManagementService service,
            WorkspacePublicationService publications,
            WorkspaceManagementProperties properties,
            ObjectMapper json) {
        this.service = service;
        this.publications = publications;
        this.properties = properties;
        this.json = json;
    }

    /**
     * 调用工作区管理接口控制器。
     *
     * @param operation 当前工作区管理接口控制器使用的操作，供其处理与状态记录使用。
     * @param authorization 当前工作区管理接口控制器使用的授权，供其处理与状态记录使用。
     * @param body 当前工作区管理接口控制器持有的正文对象，供相应处理步骤使用。
     * @return 本次操作返回的JSON节点结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @PostMapping("/{operation}")
    public JsonNode invoke(
            @PathVariable String operation,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode body) {
        if (!ServiceTokenVerifier.matches(authorization, properties.getToken()))
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "Invalid workspace management credential");
        long project = body.path("projectId").asLong();
        String agent = body.path("agentKey").asText();
        if (project <= 0
                || !properties.getProjectIds().isEmpty()
                        && !properties.getProjectIds().contains(project))
            throw new ApiException(HttpStatus.FORBIDDEN, "Workspace project is not allowed");
        try {
            new AgentCatalogKey(project, agent);
            return switch (operation) {
                case "draft" -> draft(project, agent);
                case "save" -> save(project, agent, body);
                case "current" ->
                        publications
                                .current(project, agent)
                                .map(r -> release(r, true).path("manifest"))
                                .orElse(NullNode.instance);
                case "by-id" ->
                        publications
                                .find(project, agent, body.path("releaseId").asLong())
                                .map(r -> release(r, true).path("manifest"))
                                .orElse(NullNode.instance);
                case "releases" ->
                        json.valueToTree(
                                publications.list(project, agent).stream()
                                        .map(r -> release(r, false))
                                        .toList());
                case "publish" -> publish(project, agent, body);
                case "archive-links" ->
                        archiveLinks(project, agent, body.path("releaseId").asLong());
                case "audit-list" ->
                        json.valueToTree(
                                audit.list(
                                        project,
                                        agent,
                                        body.path("path").asText(),
                                        body.path("ownerKey").asText(null),
                                        body.path("beforeSequence").asLong(0),
                                        body.path("pageSize").asInt(25)));
                case "audit-detail" ->
                        json.valueToTree(
                                audit.detail(
                                        project,
                                        agent,
                                        body.path("path").asText(),
                                        body.path("ownerKey").asText(null),
                                        body.path("sequence").asLong()));
                case "memory" ->
                        audit.memory(project, agent, body.path("ownerKey").asText())
                                .<JsonNode>map(
                                        d ->
                                                json.valueToTree(
                                                        Map.of(
                                                                "content",
                                                                d.getContent(),
                                                                "version",
                                                                d.getVersion())))
                                .orElse(json.valueToTree(Map.of("content", "", "version", 0)));
                default ->
                        throw new ApiException(HttpStatus.NOT_FOUND, "Unknown workspace operation");
            };
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        } catch (WorkspaceManagementService.Conflict conflict) {
            throw new ApiException(HttpStatus.CONFLICT, conflict.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (SecurityException forbidden) {
            throw new ApiException(HttpStatus.FORBIDDEN, forbidden.getMessage());
        } catch (NoSuchElementException missing) {
            throw new ApiException(HttpStatus.NOT_FOUND, missing.getMessage());
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前WorkspaceManagementController处理步骤使用。
     *
     * @param project 当前工作区管理接口控制器使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次操作返回的JSON节点结果。
     */
    public JsonNode draft(long project, String agent) {
        var stored = service.draft(project, agent);
        ObjectNode response = json.createObjectNode();
        response.put("agentKey", agent);
        response.put("version", stored.getVersion());
        response.putArray("skills"); // 兼容旧 DTO；当前所有 Skill 均以文件表示。
        ArrayNode assets = response.putArray("assets");
        for (var file : stored.getFiles()) {
            ObjectNode asset = assets.addObject();
            asset.put("path", file.getPath());
            asset.put("size", file.getSize());
            asset.put("sha256", file.getChecksum());
            asset.put("mediaType", file.getMediaType());
            asset.put("url", "workspace-object:" + file.getReference());
            if (file.getMediaType().startsWith("text/") || file.getMediaType().contains("json"))
                asset.put("content", new String(service.read(file), StandardCharsets.UTF_8));
        }
        return response;
    }

    /**
     * 保存工作区管理接口控制器。
     *
     * @param project 当前工作区管理接口控制器使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param body 当前工作区管理接口控制器持有的正文对象，供相应处理步骤使用。
     * @return 本次操作返回的JSON节点结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private JsonNode save(long project, String agent, JsonNode body) {
        JsonNode requested = body.path("draft");
        if (!requested.path("assets").isArray())
            throw new IllegalArgumentException("Workspace assets must be an array");
        if (requested.hasNonNull("skills")
                && (!requested.path("skills").isArray() || !requested.path("skills").isEmpty()))
            throw new IllegalArgumentException(
                    "Import Skill directories into workspace; independent Skill version selections are"
                            + " retired");
        List<WorkspaceManagementService.Input> files = new ArrayList<>();
        for (var asset : requested.path("assets")) {
            byte[] bytes =
                    asset.hasNonNull("content")
                            ? asset.path("content").asText().getBytes(StandardCharsets.UTF_8)
                            : asset.hasNonNull("base64")
                                    ? Base64.getDecoder().decode(asset.path("base64").asText())
                                    : null;
            String type =
                    asset.hasNonNull("content")
                            ? "text/plain; charset=utf-8"
                            : asset.path("mediaType").asText("application/octet-stream");
            files.add(
                    new WorkspaceManagementService.Input(
                            asset.path("path").asText(),
                            bytes,
                            asset.path("sha256").asText(),
                            type));
        }
        service.save(project, agent, requested.path("version").asLong(-1), files, operator(body));
        return draft(project, agent);
    }

    /**
     * 发布工作区管理接口控制器。
     *
     * @param project 当前工作区管理接口控制器使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param body 当前工作区管理接口控制器持有的正文对象，供相应处理步骤使用。
     * @return 本次操作返回的JSON节点结果。
     */
    private JsonNode publish(long project, String agent, JsonNode body) {
        return release(
                publications.publish(
                        project,
                        agent,
                        body.path("expectedVersion").asLong(-1),
                        body.path("notes").asText(""),
                        operator(body)),
                true);
    }

    /**
     * 计算或取得本方法声明的结果，供当前WorkspaceManagementController处理步骤使用。
     *
     * @param project 当前工作区管理接口控制器使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param id 目标对象的标识。
     * @return 本次操作返回的JSON节点结果。
     */
    private JsonNode archiveLinks(long project, String agent, long id) {
        return publications.archiveLinks(project, agent, id);
    }

    /**
     * 释放工作区管理接口控制器。
     *
     * @param release 当前工作区管理接口控制器持有的发布对象，供相应处理步骤使用。
     * @param includeManifest include清单的状态标记，用于选择当前组件的处理路径。
     * @return 本次操作返回的对象节点结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private ObjectNode release(
            WorkspaceCatalogRepository.Release release, boolean includeManifest) {
        ObjectNode response = json.createObjectNode();
        response.put("id", release.getId());
        response.put("releaseNo", release.getReleaseNo());
        response.put("releaseHash", release.getReleaseHash());
        response.put("notes", release.getNotes());
        response.put("createdBy", release.getCreatedBy());
        response.put("createdAt", release.getCreatedAt().toString());
        if (includeManifest)
            try {
                ObjectNode manifest = (ObjectNode) json.readTree(release.getManifestJson());
                manifest.put("releaseId", release.getId());
                manifest.put("releaseNo", release.getReleaseNo());
                if (manifest.path("skillRelease").isObject())
                    ((ObjectNode) manifest.path("skillRelease")).put("releaseId", release.getId());
                response.set("manifest", manifest);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid stored workspace manifest", e);
            }
        return response;
    }

    /**
     * 生成当前操作所需的operator文本，供调用方继续处理。
     *
     * @param body 当前工作区管理接口控制器持有的正文对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String operator(JsonNode body) {
        String operator = body.path("operator").asText();
        if (operator.isBlank() || operator.length() > 191)
            throw new IllegalArgumentException("Trusted operator is required");
        return operator;
    }
}
