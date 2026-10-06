package dev.horizen.agent.web.bootstrap.workspace;

import dev.horizen.agent.adapter.agentscope.workspace.release.AgentScopePublicationValidator;
import dev.horizen.agent.application.workspace.WorkspaceArchiveService;
import dev.horizen.agent.application.workspace.WorkspaceAuditService;
import dev.horizen.agent.application.workspace.WorkspaceManagementService;
import dev.horizen.agent.application.workspace.WorkspacePublicationService;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.release.WorkspacePublicationValidator;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceAuditRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceCatalogRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.web.config.WorkspaceManagementProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/**
 * 组装工作区管理、发布与迁移入口，并应用宿主准入配置。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "horizen.agent.workspace-management.enabled", havingValue = "true")
public class WorkspaceManagementConfiguration {
    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param catalog    当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param contents   资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的工作区管理服务结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Bean
    WorkspaceManagementService workspaceManagementService(
            JdbcWorkspaceCatalogRepository catalog,
            WorkspaceContentRepository contents,
            WorkspaceManagementProperties properties) {
        if (properties.getToken().isBlank())
            throw new IllegalArgumentException("Workspace management requires a service token");
        return new WorkspaceManagementService(catalog, contents);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的工作区发布校验器结果。
     */
    @Bean
    WorkspacePublicationValidator publicationValidator() {
        return new AgentScopePublicationValidator();
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param drafts 提供drafts能力的依赖，具体实现由当前组件的组装方传入。
     * @return 本次操作返回的工作区归档服务结果。
     */
    @Bean
    WorkspaceArchiveService workspaceArchives(WorkspaceManagementService drafts) {
        return new WorkspaceArchiveService(drafts);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param drafts    提供drafts能力的依赖，具体实现由当前组件的组装方传入。
     * @param catalog   当前资源目录或目录定位键，用于查找可用发布与工具。
     * @param contents  资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @param validator 当前工作区管理组装持有的校验器对象，供相应处理步骤使用。
     * @param archives  提供归档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @return 本次操作返回的工作区发布服务结果。
     */
    @Bean
    WorkspacePublicationService workspacePublications(
            WorkspaceManagementService drafts,
            JdbcWorkspaceCatalogRepository catalog,
            WorkspaceContentRepository contents,
            WorkspacePublicationValidator validator,
            WorkspaceArchiveService archives) {
        return new WorkspacePublicationService(drafts, catalog, contents, validator, archives);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param audit     提供审计能力的依赖，具体实现由当前组件的组装方传入。
     * @param contents  资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @param documents 提供文档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @return 本次操作返回的工作区审计服务结果。
     */
    @Bean
    WorkspaceAuditService workspaceAuditService(
            JdbcWorkspaceAuditRepository audit,
            WorkspaceContentRepository contents,
            JdbcWorkspaceDocumentRepository documents) {
        return new WorkspaceAuditService(audit, contents, documents);
    }
}
