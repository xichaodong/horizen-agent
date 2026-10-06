package dev.horizen.agent.application.session;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.execution.session.AgentSession;
import dev.horizen.agent.execution.session.SessionStatus;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.SessionTurnStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Session 用例，实现仅依赖领域仓储接口。
 */
public final class SessionApplicationService {
    /**
     * 会话对象或会话索引，按相应的归属键定位数据。
     */
    private final SessionTurnStore sessions;

    /**
     * 时间来源，用于计算更新时间、过期时间或执行时限。
     */
    private final Clock clock;

    /**
     * 创建会话应用服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
     */
    public SessionApplicationService(SessionTurnStore sessions) {
        this(sessions, Clock.systemUTC());
    }

    /**
     * 创建会话应用服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions 会话对象或会话索引，按相应的归属键定位数据。
     * @param clock    时间来源，用于计算更新时间、过期时间或执行时限。
     */
    public SessionApplicationService(SessionTurnStore sessions, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 查询指定隔离会话当前或最近一次执行的状态。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的会话执行视图结果。
     */
    public SessionExecutionView execution(String ownerKey, String sessionId) {
        AgentTurn turn = sessions.findLatestTurn(ownerKey, sessionId).orElse(null);
        return turn == null
                ? SessionExecutionView.idle(sessionId)
                : new SessionExecutionView(
                sessionId,
                turn.getTurnId(),
                turn.getStatus(),
                turn.getStartedAt(),
                turn.getFinishedAt(),
                turn.getFailureCode());
    }

    /**
     * 读取指定归属的会话目录页，并计算继续读取的游标。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param limit    本次处理或返回数量上限。
     * @param offset   本次读取的起始偏移。
     * @return 本次操作返回的会话页结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SessionPage list(String ownerKey, int limit, int offset) {
        if (limit <= 0 || limit > 100) {
            throw new ApplicationError(
                    ApplicationError.Code.INVALID_ARGUMENT, "limit 必须在 1 到 100 之间");
        }
        if (offset < 0) {
            throw new ApplicationError(ApplicationError.Code.INVALID_ARGUMENT, "cursor 非法");
        }
        List<AgentSession> values = sessions.listSessions(ownerKey, limit + 1, offset);
        boolean hasMore = values.size() > limit;
        List<SessionSummaryView> page =
                values.stream().limit(limit).map(session -> summary(ownerKey, session)).toList();
        return new SessionPage(page, hasMore, hasMore ? offset + limit : -1);
    }

    /**
     * 校验标题后更新原会话的展示名称。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param title     当前会话应用服务的可读标题，供宿主界面展示。
     * @return 本次操作返回的会话摘要视图结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SessionSummaryView rename(String ownerKey, String sessionId, String title) {
        String normalized = validateTitle(title);
        if (!sessions.renameSession(ownerKey, sessionId, normalized, now())) {
            throw new ApplicationError(ApplicationError.Code.NOT_FOUND, "Session 不存在");
        }
        return summary(ownerKey, requiredSession(ownerKey, sessionId));
    }

    /**
     * 更新会话置顶状态，供会话目录排序使用。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param pinned    会话是否置顶，影响会话目录展示顺序。
     * @return 本次操作返回的会话摘要视图结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SessionSummaryView setPinned(String ownerKey, String sessionId, boolean pinned) {
        if (!sessions.setSessionPinned(ownerKey, sessionId, pinned, now())) {
            throw new ApplicationError(ApplicationError.Code.NOT_FOUND, "Session 不存在");
        }
        return summary(ownerKey, requiredSession(ownerKey, sessionId));
    }

    /**
     * 将原会话归档并保留对应的正式历史。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void archive(String ownerKey, String sessionId) {
        AgentSession current = requiredSession(ownerKey, sessionId);
        if (current.getStatus() == SessionStatus.ARCHIVED) {
            return;
        }
        if (current.getActiveTurnId() != null) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "Session 正在执行，不能删除");
        }
        if (!sessions.archiveSession(ownerKey, sessionId, now())) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "Session 状态已变化，请刷新后重试");
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前SessionApplicationService处理步骤使用。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的Agent会话结果。
     */
    private AgentSession requiredSession(String ownerKey, String sessionId) {
        return sessions.findSession(ownerKey, sessionId)
                .orElseThrow(
                        () -> new ApplicationError(ApplicationError.Code.NOT_FOUND, "Session 不存在"));
    }

    /**
     * 把持久会话记录转换为目录展示所需的摘要。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param session  当前会话应用服务持有的会话对象，供相应处理步骤使用。
     * @return 本次操作返回的会话摘要视图结果。
     */
    private SessionSummaryView summary(String ownerKey, AgentSession session) {
        AgentTurn latest = sessions.findLatestTurn(ownerKey, session.getSessionId()).orElse(null);
        return new SessionSummaryView(
                session.getSessionId(),
                session.getTitle(),
                session.isPinned(),
                latest == null ? null : latest.getStatus(),
                latest == null ? null : latest.getTurnId(),
                session.getActiveTurnId(),
                session.getLastMessageAt(),
                session.getCreatedAt(),
                session.getUpdatedAt());
    }

    /**
     * 限制会话标题的空值、长度与可接受文本，避免无效目录名称。
     *
     * @param rawTitle 当前会话应用服务使用的原始标题，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String validateTitle(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.strip();
        if (title.isEmpty() || title.length() > 255 || title.indexOf('\0') >= 0) {
            throw new ApplicationError(
                    ApplicationError.Code.INVALID_ARGUMENT, "title 必须为 1 到 255 个字符");
        }
        return title;
    }

    /**
     * 计算或取得本方法声明的结果，供当前SessionApplicationService处理步骤使用。
     *
     * @return 本次操作返回的时间点结果。
     */
    private Instant now() {
        return Instant.now(clock);
    }
}
