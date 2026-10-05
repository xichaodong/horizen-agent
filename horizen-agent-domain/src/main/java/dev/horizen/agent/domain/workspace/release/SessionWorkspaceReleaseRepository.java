package dev.horizen.agent.domain.workspace.release;

import java.util.Optional;

/** Session 拥有一个完整发布版本，实现必须原子绑定。 */
public interface SessionWorkspaceReleaseRepository {
    /**
     * 查找会话工作区发布仓储。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<SessionWorkspaceRelease> find(String ownerKey, String sessionId);

    /** 首次成功绑定生效；返回持久化绑定，后续调用不修改它。 */
    SessionWorkspaceRelease bindIfAbsent(
            String ownerKey, String sessionId, SessionWorkspaceRelease selected);
}
