package dev.horizen.agent.execution.session;

import lombok.Value;

/**
 * 身份由宿主提供；未指定目标时，搜索其他可见 Session。
 */
@Value
public class SessionHistoryQuery {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    String ownerKey;

    /**
     * 本次操作所在的会话标识，用于限定当前会话查询范围。
     */
    String currentSessionId;

    /**
     * 目标会话的标识，用于关联相应记录或执行。
     */
    String targetSessionId;

    /**
     * 当前历史或记忆检索使用的关键词。
     */
    String keyword;

    /**
     * 会话消息的标识，用于历史查询与过程事件关联。
     */
    String messageId;

    /**
     * 正文读取的字符起点，用于分段返回长内容。
     */
    int textOffset;

    /**
     * 本次查询或处理数量的上限。
     */
    int limit;

    /**
     * 查询或内容读取的起始偏移量。
     */
    int offset;

    /**
     * 创建会话历史查询，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey         宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param currentSessionId 本次操作所在的会话标识，用于限定当前会话查询范围。
     * @param targetSessionId  目标会话的标识，用于关联相应记录或执行。
     * @param keyword          当前会话历史查询使用的keyword，供其处理与状态记录使用。
     * @param messageId        会话消息的标识，用于历史查询与过程事件关联。
     * @param textOffset       当前会话历史查询使用的文本偏移，供其处理与状态记录使用。
     * @param limit            本次处理或返回数量上限。
     * @param offset           本次读取的起始偏移。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SessionHistoryQuery(
            String ownerKey,
            String currentSessionId,
            String targetSessionId,
            String keyword,
            String messageId,
            int textOffset,
            int limit,
            int offset) {
        this.ownerKey = required(ownerKey, 255);
        this.currentSessionId = required(currentSessionId, 255);
        this.targetSessionId = optional(targetSessionId);
        this.keyword = keyword;
        this.messageId = optional(messageId);
        if (this.keyword != null && (this.keyword.isBlank() || this.keyword.length() > 200))
            throw new IllegalArgumentException("query must contain 1 to 200 characters");
        if (limit < 1
                || limit > (this.keyword == null ? 100 : 20)
                || offset < 0
                || offset > 1_000_000
                || textOffset < 0
                || textOffset > 1_000_000)
            throw new IllegalArgumentException("invalid history pagination");
        if (this.keyword == null && this.targetSessionId == null)
            throw new IllegalArgumentException("read requires a Session");
        if (this.messageId != null
                && (this.targetSessionId == null || this.keyword != null || offset != 0))
            throw new IllegalArgumentException(
                    "message_id requires session_id and cannot be combined with query or offset");
        if (textOffset != 0 && this.messageId == null)
            throw new IllegalArgumentException("text_offset requires message_id");
        this.textOffset = textOffset;
        this.limit = limit;
        this.offset = offset;
    }

    /**
     * 检查searching对应的条件，供调用方选择后续处理分支。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean searching() {
        return keyword != null;
    }

    /**
     * 生成当前操作所需的required文本，供调用方继续处理。
     *
     * @param value   待校验、转换或保存的原始值。
     * @param maximum 当前会话历史查询使用的最大，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String required(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException("invalid history identity");
        return value;
    }

    /**
     * 生成当前操作所需的optional文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String optional(String value) {
        return value == null ? null : required(value, 191);
    }
}
