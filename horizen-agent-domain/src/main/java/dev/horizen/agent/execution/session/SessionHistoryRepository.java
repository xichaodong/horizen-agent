package dev.horizen.agent.execution.session;

import java.util.Optional;

/** 长度受限的历史召回，按可信当前 Session 的所有者和目录隔离。 */
public interface SessionHistoryRepository {
    /** 空结果表示来源或目标 Session 不可见；存储失败必须向上传播。 */
    Optional<SessionHistoryPage> queryHistory(SessionHistoryQuery query);
}
