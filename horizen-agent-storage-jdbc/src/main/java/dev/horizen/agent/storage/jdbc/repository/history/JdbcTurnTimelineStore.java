package dev.horizen.agent.storage.jdbc.repository.history;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.execution.turn.TurnTimelineEvent;
import dev.horizen.agent.execution.turn.TurnTimelineStore;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.TurnTimelineMapper;
import dev.horizen.agent.storage.jdbc.model.ConversationHistoryRow;
import dev.horizen.agent.storage.jdbc.support.RowValues;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * 共享历史的时间线视图，不将消息或卡片正文复制到另一行。
 */
public class JdbcTurnTimelineStore implements TurnTimelineStore {
    /**
     * 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    private final TurnTimelineMapper mapper;

    /**
     * 创建JDBC执行时间线存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    public JdbcTurnTimelineStore(DataSource dataSource) {
        this.mapper = MyBatisSessions.create(dataSource).getMapper(TurnTimelineMapper.class);
    }

    /**
     * 供服务 IoC 容器注入依赖的构造方法。
     */
    public JdbcTurnTimelineStore(TurnTimelineMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * 追加JDBC执行时间线存储。
     *
     * @param ownerKey    宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId   会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId      单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param payloadJson 历史或协议负载的 JSON 表示，供读取时恢复类型化数据。
     * @param createdAt   当前记录的创建时间。
     * @return 本次操作返回的执行时间线事件结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public TurnTimelineEvent append(
            String ownerKey,
            String sessionId,
            String turnId,
            String payloadJson,
            Instant createdAt) {
        ObjectNode event = JdbcHistoryJson.object(payloadJson);
        String kind = event.path("type").asText();
        if ("done".equals(kind) && event.path("source").asText("").isEmpty()) {
            var messageIds = mapper.selectMessageIdsForTurn(ownerKey, sessionId, turnId);
            for (String messageId : messageIds) {
                TurnTimelineEvent attached =
                        attach(ownerKey, sessionId, turnId, "MESSAGE", messageId, event, createdAt);
                if (attached != null) return attached;
            }
        } else if ("presentation_created".equals(kind)) {
            String details = event.path("details").asText("");
            if (!details.isEmpty()) {
                String blockId =
                        JdbcHistoryJson.object(details).path("block").path("blockId").asText();
                TurnTimelineEvent attached =
                        attach(
                                ownerKey,
                                sessionId,
                                turnId,
                                "PRESENTATION",
                                blockId,
                                event,
                                createdAt);
                if (attached != null) return attached;
            }
        }
        ConversationHistoryRow row = new ConversationHistoryRow();
        row.setOwnerKey(ownerKey);
        row.setSessionId(sessionId);
        row.setTurnId(turnId);
        row.setRecordId(UUID.randomUUID().toString());
        row.setPayloadJson(payloadJson);
        row.setCreatedAt(Timestamp.from(createdAt));
        row.setUpdatedAt(Timestamp.from(createdAt));
        int updated = mapper.insertEvent(row);
        Long sequence = row.getHistorySequence();
        if (updated != 1 || sequence == null)
            throw new IllegalStateException("history event write failed");
        // EVENT 行直接使用其主序号，确保读者不会看到尚未完成关联的事件。
        return new TurnTimelineEvent(
                sequence.longValue(), ownerKey, sessionId, turnId, payloadJson, createdAt);
    }

    /**
     * 关联JDBC执行时间线存储。
     *
     * @param owner   当前JDBC执行时间线存储使用的数据归属，供其处理与状态记录使用。
     * @param session 当前JDBC执行时间线存储使用的会话，供其处理与状态记录使用。
     * @param turn    当前JDBC执行时间线存储使用的执行，供其处理与状态记录使用。
     * @param type    当前操作使用的目标类型或类别。
     * @param id      目标对象的标识。
     * @param event   当前JDBC执行时间线存储持有的事件对象，供相应处理步骤使用。
     * @param at      当前JDBC执行时间线存储持有的时间对象，供相应处理步骤使用。
     * @return 本次操作返回的执行时间线事件结果。
     */
    private TurnTimelineEvent attach(
            String owner,
            String session,
            String turn,
            String type,
            String id,
            ObjectNode event,
            Instant at) {
        var bodies = mapper.selectHistoryPayload(owner, type, id, session, turn);
        if (bodies.isEmpty()) return null;
        ObjectNode body = JdbcHistoryJson.object(bodies.get(0));
        ObjectNode header = event.deepCopy();
        if ("MESSAGE".equals(type)) {
            if (!"ASSISTANT".equals(body.path("role").asText())
                    || !"FINAL".equals(body.path("status").asText())) return null;
            String text = event.path("text").asText("");
            String content = body.path("content").asText();
            if (!text.endsWith(content)) return null;
            header.remove("text");
            // 宿主可以添加展示前缀，例如模拟数据提示；保留前缀，无需复制正文。
            String prefix = text.substring(0, text.length() - content.length());
            if (!prefix.isEmpty()) header.put("_history_text_prefix", prefix);
        } else {
            ObjectNode details = JdbcHistoryJson.object(event.path("details").asText());
            if (details.size() != 2
                    || !body.path("block").equals(details.path("block"))
                    || !body.path("toolCallId").equals(details.path("toolCallId"))) return null;
            header.remove("details");
        }
        mapper.attachTimelineEvent(
                JdbcHistoryJson.encode(header), Timestamp.from(at), owner, type, id, session, turn);
        return RowValues.mapOne(
                mapper.selectTimelineEvent(owner, type, id, session, turn), rs -> map(rs));
    }

    /**
     * 查询列表中的目标范围会话。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<TurnTimelineEvent> listForSession(String ownerKey, String sessionId) {
        return mapper.selectListForSession(ownerKey, sessionId).stream()
                .map(rs -> map(rs))
                .toList();
    }

    /**
     * 映射JDBC执行时间线存储。
     *
     * @param rs 当前JDBC执行时间线存储持有的rs对象，供相应处理步骤使用。
     * @return 本次操作返回的执行时间线事件结果。
     */
    private static TurnTimelineEvent map(ConversationHistoryRow rs) {
        String type = rs.getRecordType();
        String payload = rs.getPayloadJson();
        Timestamp at = rs.getCreatedAt();
        if (!"EVENT".equals(type)) {
            ObjectNode body = JdbcHistoryJson.object(payload);
            ObjectNode event = JdbcHistoryJson.object(rs.getTimelinePayloadJson());
            if ("MESSAGE".equals(type)) {
                String prefix = event.path("_history_text_prefix").asText("");
                event.remove("_history_text_prefix");
                event.put("text", prefix + body.path("content").asText());
            } else {
                ObjectNode details = JdbcHistoryJson.JSON.createObjectNode();
                details.set("toolCallId", body.path("toolCallId"));
                details.set("block", body.path("block"));
                event.put("details", JdbcHistoryJson.encode(details));
            }
            payload = JdbcHistoryJson.encode(event);
            at = rs.getTimelineCreatedAt();
        }
        return new TurnTimelineEvent(
                rs.getEventSequence(),
                rs.getOwnerKey(),
                rs.getSessionId(),
                rs.getTurnId(),
                payload,
                at.toInstant());
    }
}
