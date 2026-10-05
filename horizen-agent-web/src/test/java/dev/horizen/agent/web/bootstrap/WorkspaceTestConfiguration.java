package dev.horizen.agent.web.bootstrap;

import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceSnapshotRepository;
import dev.horizen.agent.web.config.RuntimeStorageProperties;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 显式测试替身，在测试的两个应用宿主之间保留同一实例。 */
@TestConfiguration(proxyBeanMethods = false)
public class WorkspaceTestConfiguration {
    @Primary
    @Bean(destroyMethod = "")
    WorkspaceContentRepository workspaceTestContents(RuntimeStorageProperties storage) {
        return InMemoryWorkspaceContentRepository.shared(storage.getJdbcUrl());
    }

    @Primary
    @Bean(destroyMethod = "")
    WorkspaceSnapshotRepository workspaceTestArchives(RuntimeStorageProperties storage) {
        return InMemoryWorkspaceSnapshotRepository.shared(storage.getJdbcUrl());
    }
}
