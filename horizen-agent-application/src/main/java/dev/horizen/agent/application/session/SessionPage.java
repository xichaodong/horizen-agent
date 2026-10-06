package dev.horizen.agent.application.session;

import lombok.Getter;

import java.util.List;

/**
 * 会话目录分页结果，附带继续读取所需的游标信息。
 */
@Getter
public final class SessionPage {
    /**
     * 会话对象或会话索引，按相应的归属键定位数据。
     */
    private final List<SessionSummaryView> sessions;

    /**
     * 当前分页结果之后是否仍有数据可继续读取。
     */
    private final boolean hasMore;

    /**
     * 下一页可以继续读取的位置；按当前分页协议解释。
     */
    private final int nextOffset;

    /**
     * 创建会话页，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessions   会话对象或会话索引，按相应的归属键定位数据。
     * @param hasMore    当前分页结果之后是否仍有数据可继续读取。
     * @param nextOffset 当前会话页使用的下一个偏移，供其处理与状态记录使用。
     */
    public SessionPage(List<SessionSummaryView> sessions, boolean hasMore, int nextOffset) {
        this.sessions = List.copyOf(sessions);
        this.hasMore = hasMore;
        this.nextOffset = nextOffset;
    }
}
