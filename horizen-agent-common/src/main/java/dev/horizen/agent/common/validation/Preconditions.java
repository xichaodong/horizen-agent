package dev.horizen.agent.common.validation;

/**
 * 基础校验工具；错误消息和规范化语义由调用者决定。
 */
public final class Preconditions {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private Preconditions() {
    }

    /**
     * 取得并校验文本。
     *
     * @param value   待校验、转换或保存的原始值。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    /**
     * 取得并校验文本。
     *
     * @param value         待校验、转换或保存的原始值。
     * @param message       用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @param maxCharacters 当前Preconditions使用的最大Characters，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String requireText(String value, String message, int maxCharacters) {
        String text = requireText(value, message);
        if (text.codePointCount(0, text.length()) > maxCharacters) {
            throw new IllegalArgumentException(message + " (max " + maxCharacters + " characters)");
        }
        return text;
    }
}
