package dev.horizen.agent.storage.jdbc.converter;

import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;
import dev.horizen.agent.execution.session.AgentSession;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.session.MessageRole;
import dev.horizen.agent.execution.session.MessageStatus;
import dev.horizen.agent.execution.session.SessionStatus;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.model.ConversationHistoryRow;
import dev.horizen.agent.storage.jdbc.model.SessionRow;
import dev.horizen.agent.storage.jdbc.model.TurnRow;
import dev.horizen.agent.storage.jdbc.support.RowValues;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 执行领域内部的 JDBC 行和时间戳转换。 */
public final class JdbcExecutionMappings {
    /** 会话的固定取值，用于相应策略和边界判断。 */
    public static final Function<SessionRow, AgentSession> SESSION =
            rs -> {
                AgentSession session =
                        new AgentSession(
                                rs.getOwnerKey(),
                                rs.getSessionId(),
                                SessionStatus.valueOf(rs.getStatus()),
                                rs.getActiveTurnId(),
                                rs.getCreatedBy(),
                                rs.getTitle(),
                                rs.getPinned(),
                                RowValues.nullableInstant(rs.getLastMessageAt()) == null
                                        ? RowValues.instant(rs.getUpdatedAt())
                                        : RowValues.instant(rs.getLastMessageAt()),
                                RowValues.instant(rs.getCreatedAt()),
                                RowValues.instant(rs.getUpdatedAt()),
                                rs.getVersion());
                session.setSnapshotId(rs.getSnapshotId());
                String releaseHash = rs.getWorkspaceReleaseHash();
                if (releaseHash != null)
                    session.setWorkspaceRelease(
                            new SessionWorkspaceRelease(
                                    new AgentCatalogKey(rs.getProjectId(), rs.getAgentKey()),
                                    rs.getWorkspaceReleaseId(),
                                    releaseHash));
                return session;
            };

    /** 执行的固定取值，用于相应策略和边界判断。 */
    public static final Function<TurnRow, AgentTurn> TURN =
            rs ->
                    new AgentTurn(
                            rs.getOwnerKey(),
                            rs.getSessionId(),
                            rs.getTurnId(),
                            rs.getRequestId(),
                            rs.getActorId(),
                            TurnStatus.valueOf(rs.getStatus()),
                            rs.getExecutorId(),
                            RowValues.instant(rs.getStartedAt()),
                            RowValues.nullableInstant(rs.getFinishedAt()),
                            RowValues.nullableInstant(rs.getDeadlineAt()),
                            RowValues.nullableInstant(rs.getLeaseExpiresAt()),
                            rs.getFailureCode(),
                            RowValues.instant(rs.getCreatedAt()),
                            RowValues.instant(rs.getUpdatedAt()),
                            rs.getVersion());

    /** 消息的固定取值，用于相应策略和边界判断。 */
    public static final Function<ConversationHistoryRow, ConversationMessage> MESSAGE =
            rs -> {
                var payload = JdbcHistoryJson.object(rs.getPayloadJson());
                ConversationMessage message =
                        new ConversationMessage(
                                rs.getOwnerKey(),
                                rs.getSessionId(),
                                rs.getTurnId(),
                                rs.getRecordId(),
                                MessageRole.valueOf(payload.path("role").asText()),
                                MessageStatus.valueOf(payload.path("status").asText()),
                                payload.path("content").asText(),
                                rs.getMessageSequence(),
                                RowValues.instant(rs.getCreatedAt()),
                                RowValues.instant(rs.getUpdatedAt()));
                List<String> ids = new ArrayList<>();
                payload.path("artifactIds").forEach(id -> ids.add(id.asText()));
                message.setArtifactIds(ids);
                return message;
            };

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private JdbcExecutionMappings() {}

    /**
     * 计算或取得本方法声明的结果，供当前JdbcExecutionMappings处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的时间戳结果。
     */
    public static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcExecutionMappings处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的时间戳结果。
     */
    public static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : timestamp(value);
    }
}
