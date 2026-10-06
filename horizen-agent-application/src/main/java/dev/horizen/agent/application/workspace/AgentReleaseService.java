package dev.horizen.agent.application.workspace;

import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.AgentReleaseRepository;
import dev.horizen.agent.domain.workspace.release.AgentReleaseSnapshot;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceReleaseRepository;

import java.util.Objects;
import java.util.Optional;

/**
 * 每次执行和恢复均读取其 Session 拥有的完整发布版本。
 */
public final class AgentReleaseService {
    /**
     * 当前 Project 与 Agent 的发布目录定位键。
     */
    private final AgentCatalogKey catalog;

    /**
     * 获取发布描述并持有已校验工作区内容的仓储。
     */
    private final AgentReleaseRepository repository;

    /**
     * 原子保存 owner 与 Session 的发布绑定，恢复必须复用该绑定。
     */
    private final SessionWorkspaceReleaseRepository bindings;

    /**
     * 最近一次成功获取并校验的完整发布描述，供状态接口展示。
     */
    private volatile AgentReleaseManifest lastValidated;

    /**
     * 最近一次执行准备失败的异常类型名称；成功取得发布后清空。
     */
    private volatile String lastFailure;

    /**
     * 创建Agent发布服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param catalog    当前 Project 与 Agent 的发布目录定位键。
     * @param repository 获取发布描述并持有已校验工作区内容的仓储。
     * @param bindings   原子保存 owner 与 Session 的发布绑定，恢复必须复用该绑定。
     */
    public AgentReleaseService(
            AgentCatalogKey catalog,
            AgentReleaseRepository repository,
            SessionWorkspaceReleaseRepository bindings) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    /**
     * 返回最近一次成功校验并取得的完整发布；尚未成功取得发布时为空。
     *
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    public Optional<AgentReleaseManifest> current() {
        return Optional.ofNullable(lastValidated);
    }

    /**
     * 返回最近一次发布准备失败的异常类型摘要；成功取得发布后清空。
     *
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /**
     * 为本次执行取得会话绑定的发布。首次执行先准备内容再原子绑定；恢复不能回退到其他发布。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param resume    恢复执行的状态标记，用于选择当前组件的处理路径。
     * @return 本次操作返回的Agent发布快照结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public AgentReleaseSnapshot beginExecution(String ownerKey, String sessionId, boolean resume) {
        try {
            SessionWorkspaceRelease bound = bindings.find(ownerKey, sessionId).orElse(null);
            if (bound != null) return acquireBound(bound);
            if (resume)
                throw new IllegalStateException(
                        "WORKSPACE_RELEASE_UNBOUND: cannot resume without the original Session publication");
            AgentReleaseManifest manifest =
                    repository
                            .findCurrent(catalog)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "No Agent workspace has been published"));
            validate(manifest, null);
            // 先完成下载与校验，再通过短事务原子绑定数据库记录；事务中不调用网络。
            AgentReleaseSnapshot prepared = repository.acquire(manifest);
            SessionWorkspaceRelease selected = SessionWorkspaceRelease.of(manifest);
            try {
                SessionWorkspaceRelease winner =
                        bindings.bindIfAbsent(ownerKey, sessionId, selected);
                if (selected.equals(winner)) return accepted(prepared);
                prepared.close();
                return acquireBound(winner);
            } catch (RuntimeException error) {
                prepared.close();
                throw error;
            }
        } catch (RuntimeException error) {
            lastFailure = error.getClass().getSimpleName();
            throw error; // 旧发布版本缺失时，绝不能回退到其他发布版本。
        }
    }

    /**
     * 按已有会话绑定取得原发布，并核对目录、发布标识及内容哈希。
     *
     * @param bound 当前Agent发布服务持有的已绑定对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent发布快照结果。
     * @throws SecurityException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private AgentReleaseSnapshot acquireBound(SessionWorkspaceRelease bound) {
        if (!catalog.equals(bound.getCatalog()))
            throw new SecurityException("Session belongs to another Project or Agent");
        AgentReleaseManifest manifest =
                repository
                        .findById(catalog, bound.getReleaseId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Bound Session workspace release is unavailable"));
        validate(manifest, bound);
        return accepted(repository.acquire(manifest));
    }

    /**
     * 校验发布的 Project、Agent 与会话绑定是否一致，拒绝跨目录或变更身份的发布。
     *
     * @param manifest 当前Agent发布服务持有的清单对象，供相应处理步骤使用。
     * @param bound    当前Agent发布服务持有的已绑定对象，供相应处理步骤使用。
     * @throws SecurityException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void validate(AgentReleaseManifest manifest, SessionWorkspaceRelease bound) {
        if (manifest.getProjectId() != catalog.getProjectId()
                || !manifest.getAgentKey().equals(catalog.getAgentKey())) {
            throw new SecurityException("Agent publication scope mismatch");
        }
        if (bound != null
                && (manifest.getReleaseId() != bound.getReleaseId()
                || !manifest.getReleaseHash().equals(bound.getReleaseHash()))) {
            throw new SecurityException("Bound Session publication identity changed");
        }
    }

    /**
     * 记录本次成功取得的发布并清空失败摘要，返回仍由调用方持有的快照租约。
     *
     * @param snapshot 当前Agent发布服务持有的快照对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent发布快照结果。
     */
    private AgentReleaseSnapshot accepted(AgentReleaseSnapshot snapshot) {
        lastValidated = snapshot.getManifest();
        lastFailure = null;
        return snapshot;
    }
}
