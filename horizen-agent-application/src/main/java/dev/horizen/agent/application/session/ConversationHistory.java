package dev.horizen.agent.application.session;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.presentation.PresentationRecord;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.turn.AgentTurn;
import dev.horizen.agent.execution.turn.TurnTimelineEvent;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/** Session 持久化历史；活跃 Redis 事件流的恢复通过独立查询处理。 */
@Getter
public final class ConversationHistory {
    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private final String sessionId;

    /** 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
    private final List<ConversationMessage> messages;

    /** 需要持久化或展示的结构化呈现块集合。 */
    private final List<PresentationRecord> presentations;

    /** 存储正式过程事件的时间线端口，供持久化与刷新恢复使用。 */
    private final List<TurnTimelineEvent> timeline;

    /** 输入产物集合按条件执行的索引映射，供按键查找或归并当前组件的数据。 */
    private final Map<String, List<Artifact>> inputArtifactsByTurn;

    /** 当前会话最近一次执行的事实记录；没有执行历史时可以为空。 */
    private final AgentTurn latestTurn;

    /**
     * 创建对话历史，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param presentations 需要持久化或展示的结构化呈现块集合。
     * @param timeline 时间线的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param inputArtifactsByTurn 输入产物集合按条件执行的索引映射，供按键查找或归并当前组件的数据。
     * @param latestTurn 当前对话历史持有的最近版本执行对象，供相应处理步骤使用。
     */
    public ConversationHistory(
            String sessionId,
            List<ConversationMessage> messages,
            List<PresentationRecord> presentations,
            List<TurnTimelineEvent> timeline,
            Map<String, List<Artifact>> inputArtifactsByTurn,
            AgentTurn latestTurn) {
        this.sessionId = sessionId;
        this.messages = List.copyOf(messages);
        this.presentations = List.copyOf(presentations);
        this.timeline = List.copyOf(timeline);
        this.inputArtifactsByTurn = Map.copyOf(inputArtifactsByTurn);
        this.latestTurn = latestTurn;
    }
}
