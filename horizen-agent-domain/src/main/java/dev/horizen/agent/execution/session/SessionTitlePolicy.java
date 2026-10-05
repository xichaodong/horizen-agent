package dev.horizen.agent.execution.session;

/** 首次 Turn 创建 Session 时采用的默认标题策略。 */
public final class SessionTitlePolicy {
    /** 最大代码POINTS的固定取值，用于相应策略和边界判断。 */
    private static final int MAX_CODE_POINTS = 20;

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private SessionTitlePolicy() {}

    /**
     * 从输入构造首个消息。
     *
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @return 本次处理生成或读取的文本。
     */
    public static String fromFirstMessage(String message) {
        String value = message == null ? "" : message.strip();
        int newline = value.indexOf('\n');
        if (newline >= 0) value = value.substring(0, newline).strip();
        if (value.isEmpty()) return "新对话";
        int end =
                value.offsetByCodePoints(
                        0, Math.min(value.codePointCount(0, value.length()), MAX_CODE_POINTS));
        return value.substring(0, end);
    }
}
