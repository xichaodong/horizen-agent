package dev.horizen.agent.storage.jdbc.config;

import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.storage.jdbc.mapper.ApprovalMapper;
import dev.horizen.agent.storage.jdbc.mapper.ArtifactMapper;
import dev.horizen.agent.storage.jdbc.mapper.AskUserMapper;
import dev.horizen.agent.storage.jdbc.mapper.PresentationMapper;
import dev.horizen.agent.storage.jdbc.mapper.SessionHistoryMapper;
import dev.horizen.agent.storage.jdbc.mapper.SessionTurnMapper;
import dev.horizen.agent.storage.jdbc.mapper.SessionWorkspaceReleaseMapper;
import dev.horizen.agent.storage.jdbc.mapper.TurnTimelineMapper;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceAuditMapper;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceCatalogMapper;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceDocumentMapper;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceFileMigrationMapper;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceSnapshotPointerMapper;
import dev.horizen.agent.storage.jdbc.migration.JdbcWorkspaceFileMigration;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcArtifactStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcPresentationStore;
import dev.horizen.agent.storage.jdbc.repository.history.JdbcTurnTimelineStore;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcApprovalStore;
import dev.horizen.agent.storage.jdbc.repository.interaction.JdbcAskUserStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionHistoryRepository;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionWorkspaceReleaseRepository;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcWorkspaceSnapshotPointerRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceAuditRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceCatalogRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;
import dev.horizen.agent.transaction.UnitOfWork;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * 按作用域显式配置持久化 Bean，不创建表结构，也不选择未命名的数据源。
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan(
        basePackages = "dev.horizen.agent.storage.jdbc.mapper",
        sqlSessionTemplateRef = "agentSqlSessionTemplate")
public class AgentJdbcConfiguration {
    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param source 待解析或转换的来源对象。
     * @return 本次操作返回的平台事务管理器结果。
     */
    @Bean("agentTransactionManager")
    PlatformTransactionManager transactions(@Qualifier("agentDataSource") DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentJdbcConfiguration处理步骤使用。
     *
     * @param source  待解析或转换的来源对象。
     * @param options 可供当前请求选择的选项或策略集合。
     * @return 本次操作返回的SQL会话工厂结果。
     */
    @Bean("agentSqlSessionFactory")
    SqlSessionFactory factory(
            @Qualifier("agentDataSource") DataSource source, JdbcStorageOptions options) {
        return MyBatisSessions.factory(source, options.getQueryTimeout());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param factory 提供工厂能力的依赖，具体实现由当前组件的组装方传入。
     * @return 本次操作返回的SQL会话模板结果。
     */
    @Bean("agentSqlSessionTemplate")
    SqlSessionTemplate session(@Qualifier("agentSqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的事务工作单元结果。
     */
    @Bean("agentUnitOfWork")
    UnitOfWork unitOfWork() {
        return new JdbcUnitOfWork();
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次操作返回的JDBC会话历史仓储结果。
     */
    @Bean
    JdbcSessionHistoryRepository sessionHistory(SessionHistoryMapper mapper) {
        return new JdbcSessionHistoryRepository(mapper);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper  本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param tx      当前AgentJDBC组装持有的事务对象，供相应处理步骤使用。
     * @param history 提供历史能力的依赖，具体实现由当前组件的组装方传入。
     * @return 本次操作返回的JDBC会话执行存储结果。
     */
    @Bean
    JdbcSessionTurnStore sessionTurns(
            SessionTurnMapper mapper,
            @Qualifier("agentUnitOfWork") UnitOfWork tx,
            JdbcSessionHistoryRepository history) {
        return new JdbcSessionTurnStore(mapper, tx, history);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param tx     当前AgentJDBC组装持有的事务对象，供相应处理步骤使用。
     * @return 本次操作返回的JDBC审批存储结果。
     */
    @Bean
    JdbcApprovalStore approvals(
            ApprovalMapper mapper, @Qualifier("agentUnitOfWork") UnitOfWork tx) {
        return new JdbcApprovalStore(mapper, tx);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次操作返回的JDBC提问用户存储结果。
     */
    @Bean
    JdbcAskUserStore askUsers(AskUserMapper mapper) {
        return new JdbcAskUserStore(mapper);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param tx     当前AgentJDBC组装持有的事务对象，供相应处理步骤使用。
     * @return 本次操作返回的JDBC产物存储结果。
     */
    @Bean
    JdbcArtifactStore artifacts(
            ArtifactMapper mapper, @Qualifier("agentUnitOfWork") UnitOfWork tx) {
        return new JdbcArtifactStore(mapper, tx);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次操作返回的JDBC呈现存储结果。
     */
    @Bean
    JdbcPresentationStore presentations(PresentationMapper mapper) {
        return new JdbcPresentationStore(mapper);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次操作返回的JDBC执行时间线存储结果。
     */
    @Bean
    JdbcTurnTimelineStore timeline(TurnTimelineMapper mapper) {
        return new JdbcTurnTimelineStore(mapper);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次操作返回的JDBC会话工作区发布仓储结果。
     */
    @Bean
    JdbcSessionWorkspaceReleaseRepository sessionReleases(SessionWorkspaceReleaseMapper mapper) {
        return new JdbcSessionWorkspaceReleaseRepository(mapper);
    }

    /**
     * 读取快照中的指针集合。
     *
     * @param mapper  本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param options 可供当前请求选择的选项或策略集合。
     * @return 本次操作返回的JDBC工作区快照指针仓储结果。
     */
    @Bean
    JdbcWorkspaceSnapshotPointerRepository snapshotPointers(
            WorkspaceSnapshotPointerMapper mapper, JdbcStorageOptions options) {
        return new JdbcWorkspaceSnapshotPointerRepository(mapper, options.getInstanceId());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper   本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param tx       当前AgentJDBC组装持有的事务对象，供相应处理步骤使用。
     * @param contents 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @return 本次操作返回的JDBC工作区文档仓储结果。
     */
    @Bean
    JdbcWorkspaceDocumentRepository documents(
            WorkspaceDocumentMapper mapper,
            @Qualifier("agentUnitOfWork") UnitOfWork tx,
            WorkspaceContentRepository contents) {
        return new JdbcWorkspaceDocumentRepository(
                mapper, tx, contents, JdbcWorkspaceDocumentRepository.DEFAULT_MAX_DOCUMENT_BYTES);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param tx     当前AgentJDBC组装持有的事务对象，供相应处理步骤使用。
     * @return 本次操作返回的JDBC工作区目录仓储结果。
     */
    @Bean
    JdbcWorkspaceCatalogRepository workspaceCatalog(
            WorkspaceCatalogMapper mapper, @Qualifier("agentUnitOfWork") UnitOfWork tx) {
        return new JdbcWorkspaceCatalogRepository(mapper, tx);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次操作返回的JDBC工作区审计仓储结果。
     */
    @Bean
    JdbcWorkspaceAuditRepository workspaceAudit(WorkspaceAuditMapper mapper) {
        return new JdbcWorkspaceAuditRepository(mapper);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param mapper   本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param tx       当前AgentJDBC组装持有的事务对象，供相应处理步骤使用。
     * @param contents 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @return 本次操作返回的JDBC工作区文件迁移结果。
     */
    @Bean
    JdbcWorkspaceFileMigration migration(
            WorkspaceFileMigrationMapper mapper,
            @Qualifier("agentUnitOfWork") UnitOfWork tx,
            WorkspaceContentRepository contents) {
        return new JdbcWorkspaceFileMigration(mapper, tx, contents);
    }
}
