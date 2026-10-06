package dev.horizen.agent.web.bootstrap.storage;

import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;
import dev.horizen.agent.storage.bos.BosWorkspaceContentRepository;
import dev.horizen.agent.storage.bos.BosWorkspaceSnapshotRepository;
import dev.horizen.agent.web.config.SandboxSnapshotProperties;
import dev.horizen.agent.web.config.WorkspaceStorageProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 组装工作区文件内容存储与领域访问服务。
 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceContentConfiguration {
    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的工作区正文仓储结果。
     */
    @Bean
    @ConditionalOnMissingBean(WorkspaceContentRepository.class)
    @ConditionalOnProperty(name = "horizen.agent.storage.mode", havingValue = "DISTRIBUTED")
    WorkspaceContentRepository workspaceContents(WorkspaceStorageProperties properties) {
        return new BosWorkspaceContentRepository(properties.toConfig());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param snapshots  当前工作区正文组装持有的快照集合对象，供相应处理步骤使用。
     * @return 本次操作返回的工作区快照仓储结果。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(WorkspaceSnapshotRepository.class)
    @ConditionalOnProperty(name = "horizen.agent.storage.mode", havingValue = "DISTRIBUTED")
    WorkspaceSnapshotRepository sessionArchives(
            WorkspaceStorageProperties properties, SandboxSnapshotProperties snapshots) {
        return new BosWorkspaceSnapshotRepository(
                properties.directoryConfig(snapshots.getMaxArchiveBytes()),
                snapshots.getConcurrency(),
                snapshots.getTimeoutSeconds());
    }
}
