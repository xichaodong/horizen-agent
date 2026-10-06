package dev.horizen.agent.application.session;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;
import dev.horizen.agent.domain.presentation.PresentationStore;
import dev.horizen.agent.execution.session.ConversationMessage;
import dev.horizen.agent.execution.turn.SessionTurnStore;
import dev.horizen.agent.execution.turn.TurnTimelineStore;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 通过仓储接口构建持久化历史的展示视图。
 */
public final class ConversationHistoryQueryService {
    /**
     * 会话对象或会话索引，按相应的归属键定位数据。
     */
    private final SessionTurnStore sessions;

    /**
     * 需要持久化或展示的结构化呈现块集合。
     */
    private final PresentationStore presentations;

    /**
     * 存储正式过程事件的时间线端口，供持久化与刷新恢复使用。
     */
    private final TurnTimelineStore timeline;

    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    private final ArtifactStore artifacts;

    /**
     * 创建对话历史查询服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions      会话对象或会话索引，按相应的归属键定位数据。
     * @param presentations 需要持久化或展示的结构化呈现块集合。
     * @param timeline      提供时间线能力的依赖，具体实现由当前组件的组装方传入。
     * @param artifacts     产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    public ConversationHistoryQueryService(
            SessionTurnStore sessions,
            PresentationStore presentations,
            TurnTimelineStore timeline,
            ArtifactStore artifacts) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.presentations = presentations;
        this.timeline = timeline;
        this.artifacts = artifacts;
    }

    /**
     * 按归属读取会话正式消息、过程事实与呈现块，组合成刷新或恢复所需的历史。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的对话历史结果。
     */
    public ConversationHistory load(String ownerKey, String sessionId) {
        var messages = sessions.listFinalMessages(ownerKey, sessionId);
        return new ConversationHistory(
                sessionId,
                messages,
                presentations == null
                        ? List.of()
                        : presentations.listForSession(ownerKey, sessionId),
                timeline == null ? List.of() : timeline.listForSession(ownerKey, sessionId),
                inputArtifacts(ownerKey, sessionId, messages),
                sessions.findLatestTurn(ownerKey, sessionId).orElse(null));
    }

    /**
     * 从正式用户消息的资源引用中恢复输入产物集合。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param messages  消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 按返回类型约定组织的结果映射。
     */
    private Map<String, List<Artifact>> inputArtifacts(
            String ownerKey, String sessionId, List<ConversationMessage> messages) {
        if (artifacts == null) return Map.of();
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (var message : messages) {
            if (!message.getArtifactIds().isEmpty())
                grouped.computeIfAbsent(message.getTurnId(), ignored -> new LinkedHashSet<>())
                        .addAll(message.getArtifactIds());
        }
        // 同时覆盖工具加载的文件、子记录和孤立记录，以及缺少附件元数据的旧消息。
        artifacts.listReferencesForSession(ownerKey, sessionId).stream()
                .filter(reference -> reference.getRole() == ArtifactReferenceRole.INPUT)
                .forEach(
                        reference ->
                                grouped.computeIfAbsent(
                                                reference.getTurnId(),
                                                ignored -> new LinkedHashSet<>())
                                        .add(reference.getArtifactId()));
        Map<String, Optional<Artifact>> metadata = new LinkedHashMap<>();
        Map<String, List<Artifact>> result = new LinkedHashMap<>();
        grouped.forEach(
                (turnId, ids) ->
                        result.put(
                                turnId,
                                ids.stream()
                                        .map(
                                                id ->
                                                        metadata.computeIfAbsent(
                                                                id,
                                                                ignored ->
                                                                        artifacts.find(
                                                                                ownerKey, id)))
                                        .flatMap(Optional::stream)
                                        .filter(
                                                artifact ->
                                                        artifact.getState() == ArtifactState.READY)
                                        .toList()));
        return Map.copyOf(result);
    }
}
