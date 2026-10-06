package dev.horizen.agent.execution.session;

import lombok.Value;

import java.util.List;

/**
 * 会话历史页类型，承载本模块对外或内部协作所需的状态与契约。
 */
@Value
public class SessionHistoryPage {
    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    String sessionId;

    /**
     * 当前会话历史页的可读标题，供宿主界面展示。
     */
    String title;

    /**
     * 当前查询匹配的正式消息总数量。
     */
    Long totalMessages;

    /**
     * 条目集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    List<SessionHistoryEntry> entries;

    /**
     * 当前分页结果之后是否仍有数据可继续读取。
     */
    boolean hasMore;

    /**
     * 下一页可以继续读取的位置；按当前分页协议解释。
     */
    int nextOffset;

    /**
     * 创建会话历史页，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessionId     会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param title         当前会话历史页的可读标题，供宿主界面展示。
     * @param totalMessages 当前会话历史页使用的总计消息集合，供其处理与状态记录使用。
     * @param entries       条目集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param hasMore       当前分页结果之后是否仍有数据可继续读取。
     * @param nextOffset    当前会话历史页使用的下一个偏移，供其处理与状态记录使用。
     */
    public SessionHistoryPage(
            String sessionId,
            String title,
            Long totalMessages,
            List<SessionHistoryEntry> entries,
            boolean hasMore,
            int nextOffset) {
        this.sessionId = sessionId;
        this.title = title;
        this.totalMessages = totalMessages;
        this.entries = List.copyOf(entries);
        this.hasMore = hasMore;
        this.nextOffset = nextOffset;
    }
}
