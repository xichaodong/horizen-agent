package dev.horizen.agent.application.session;

import dev.horizen.agent.execution.turn.TurnStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/** 由应用层组装的 Session 列表视图。 */
@RequiredArgsConstructor
@Getter
public final class SessionSummaryView {
    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 当前会话摘要视图的可读标题，供宿主界面展示。 */
    private final String title;

    /** 会话是否置顶，影响会话目录展示顺序。 */
    private final boolean pinned;

    /** 当前会话最近一次执行的状态，用于目录摘要展示。 */
    private final TurnStatus latestTurnStatus;

    /** 最近版本执行的标识，用于关联相应记录或执行。 */
    private final String latestTurnId;

    /** 会话当前占用的执行标识；无活跃执行时为空。 */
    private final String activeTurnId;

    /** 会话最近一条正式消息的时间，供会话排序使用。 */
    private final Instant lastMessageAt;

    /** 当前记录的创建时间。 */
    private final Instant createdAt;

    /** 当前记录最近一次更新的时间。 */
    private final Instant updatedAt;
}
