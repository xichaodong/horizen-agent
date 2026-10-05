package dev.horizen.agent.storage.jdbc.repository.session;

import dev.horizen.agent.execution.session.MessageRole;
import dev.horizen.agent.execution.session.SessionHistoryEntry;
import dev.horizen.agent.execution.session.SessionHistoryPage;
import dev.horizen.agent.execution.session.SessionHistoryQuery;
import dev.horizen.agent.execution.session.SessionHistoryRepository;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.SessionHistoryMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/** 只读 MySQL JSON 召回，不维护额外搜索索引或重复消息正文。 */
public final class JdbcSessionHistoryRepository implements SessionHistoryRepository {
    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final SessionHistoryMapper mapper;

    /**
     * 创建JDBC会话历史仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param source 待解析或转换的来源对象。
     */
    public JdbcSessionHistoryRepository(DataSource source) {
        mapper = MyBatisSessions.create(source).getMapper(SessionHistoryMapper.class);
    }

    /**
     * 创建JDBC会话历史仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param mapper 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    public JdbcSessionHistoryRepository(SessionHistoryMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * 查询历史。
     *
     * @param query 当前JDBC会话历史仓储持有的查询对象，供相应处理步骤使用。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<SessionHistoryPage> queryHistory(SessionHistoryQuery query) {
        Map<String, Object> args = new HashMap<>();
        args.put("owner", query.getOwnerKey());
        args.put("current", query.getCurrentSessionId());
        args.put("target", query.getTargetSessionId());
        args.put("withMessages", false);
        if (mapper.visibleCurrent(args).isEmpty()) return Optional.empty();
        String title = null;
        if (query.getTargetSessionId() != null) {
            var titles = mapper.targetTitles(args);
            if (titles.isEmpty()) return Optional.empty();
            title = titles.get(0);
        }
        args.put("withMessages", true);
        args.put("message", query.getMessageId());
        args.put("textStart", query.getTextOffset() + 1);
        args.put("searching", query.searching());
        args.put("contentLimit", query.searching() ? 480 : 4000);
        if (query.searching()) {
            String keyword = query.getKeyword().toLowerCase(Locale.ROOT);
            args.put("keyword", keyword);
            args.put("pattern", "%" + escapeLike(keyword) + "%");
        }
        Long total = query.searching() ? null : mapper.countHistory(args);
        args.put("limit", query.getLimit() + 1);
        args.put("offset", query.getOffset());
        List<SessionHistoryEntry> entries =
                mapper.selectHistory(args).stream()
                        .map(
                                row ->
                                        new SessionHistoryEntry(
                                                row.getSessionId(),
                                                row.getTitle(),
                                                row.getTurnId(),
                                                row.getRecordId(),
                                                MessageRole.valueOf(row.getMessageRole()),
                                                row.getMessageSequence(),
                                                row.getCreatedAt().toInstant(),
                                                row.getExcerpt(),
                                                row.getContentOffset(),
                                                row.getContentLength()))
                        .toList();
        boolean more = entries.size() > query.getLimit();
        if (more) entries = new ArrayList<>(entries.subList(0, query.getLimit()));
        return Optional.of(
                new SessionHistoryPage(
                        query.getTargetSessionId(),
                        title,
                        total,
                        entries,
                        more,
                        query.getOffset() + entries.size()));
    }

    /**
     * 生成当前操作所需的escapeLike文本，供调用方继续处理。
     *
     * @param text 面向消息或事件消费者的文本内容。
     * @return 本次处理生成或读取的文本。
     */
    private static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
