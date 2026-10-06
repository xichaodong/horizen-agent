package dev.horizen.agent.storage.jdbc.repository.session;

import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceReleaseRepository;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.SessionWorkspaceReleaseMapper;
import dev.horizen.agent.storage.jdbc.model.SessionRow;

import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * 仅保存 Session 发布元数据，不持久化缓存路径、URL 或凭据。
 */
public class JdbcSessionWorkspaceReleaseRepository implements SessionWorkspaceReleaseRepository {
    /**
     * 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    private final SessionWorkspaceReleaseMapper mapper;

    /**
     * 创建JDBC会话工作区发布仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    public JdbcSessionWorkspaceReleaseRepository(DataSource dataSource) {
        this.mapper =
                MyBatisSessions.create(dataSource).getMapper(SessionWorkspaceReleaseMapper.class);
    }

    /**
     * 供服务 IoC 容器注入依赖的构造方法。
     */
    public JdbcSessionWorkspaceReleaseRepository(SessionWorkspaceReleaseMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * 查找JDBC会话工作区发布仓储。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Optional<SessionWorkspaceRelease> find(String ownerKey, String sessionId) {
        SessionRow row = mapper.selectBinding(ownerKey, sessionId);
        if (row == null) return Optional.empty();
        String hash = row.getWorkspaceReleaseHash();
        if (hash == null) {
            if (row.getProjectId() != null
                    || row.getAgentKey() != null
                    || row.getWorkspaceReleaseId() != null)
                throw new IllegalStateException("Incomplete Session workspace binding");
            return Optional.empty();
        }
        return Optional.of(
                new SessionWorkspaceRelease(
                        new AgentCatalogKey(row.getProjectId(), row.getAgentKey()),
                        row.getWorkspaceReleaseId(),
                        hash));
    }

    /**
     * 绑定条件Absent。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param selected  当前JDBC会话工作区发布仓储持有的selected对象，供相应处理步骤使用。
     * @return 本次操作返回的会话工作区发布结果。
     * @throws SecurityException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public SessionWorkspaceRelease bindIfAbsent(
            String ownerKey, String sessionId, SessionWorkspaceRelease selected) {
        Objects.requireNonNull(selected, "selected");
        mapper.bindWorkspaceReleaseIfAbsent(
                selected.getCatalog().getProjectId(),
                selected.getCatalog().getAgentKey(),
                selected.getReleaseId(),
                selected.getReleaseHash(),
                ownerKey,
                sessionId);
        SessionWorkspaceRelease bound =
                find(ownerKey, sessionId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Cannot bind workspace to a missing or inactive Session"));
        if (!bound.getCatalog().equals(selected.getCatalog())) {
            throw new SecurityException("Session belongs to another Project or Agent");
        }
        return bound;
    }
}
