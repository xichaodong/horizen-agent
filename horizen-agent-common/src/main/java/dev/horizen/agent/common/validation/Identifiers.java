package dev.horizen.agent.common.validation;

import java.util.regex.Pattern;

/** 共享语法约束，授权和所有权检查仍由调用处负责。 */
public final class Identifiers {
    /** 校验256的模式，限定允许接受的输入形式。 */
    public static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** 校验OPAQUE标识的模式，限定允许接受的输入形式。 */
    public static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    // Artifact 元数据和历史记录标识存储在 VARCHAR(191) 列中。
    /** 数据归属键最大长度的固定取值，用于相应策略和边界判断。 */
    public static final int OWNER_KEY_MAX_LENGTH = 191;

    /** 操作方标识最大长度的固定取值，用于相应策略和边界判断。 */
    public static final int ACTOR_ID_MAX_LENGTH = 191;

    /** STORED键最大长度的固定取值，用于相应策略和边界判断。 */
    public static final int STORED_KEY_MAX_LENGTH = 191;

    /** 校验产物标识的模式，限定允许接受的输入形式。 */
    public static final Pattern ARTIFACT_ID = opaque(STORED_KEY_MAX_LENGTH);

    /** 校验产物数据归属键的模式，限定允许接受的输入形式。 */
    public static final Pattern ARTIFACT_OWNER_KEY = opaque(OWNER_KEY_MAX_LENGTH);

    /** 校验产物引用标识的模式，限定允许接受的输入形式。 */
    public static final Pattern ARTIFACT_REFERENCE_ID = opaque(STORED_KEY_MAX_LENGTH);

    /** 校验STORED会话标识的模式，限定允许接受的输入形式。 */
    public static final Pattern STORED_SESSION_ID = opaque(STORED_KEY_MAX_LENGTH);

    /** 校验STORED执行标识的模式，限定允许接受的输入形式。 */
    public static final Pattern STORED_TURN_ID = opaque(STORED_KEY_MAX_LENGTH);

    // 公开聊天命令采用比存储列更严格的键格式。
    /** 对话键正则表达式使用的固定标识或协议文本。 */
    public static final String CHAT_KEY_REGEX = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    /** 校验对话键的模式，限定允许接受的输入形式。 */
    public static final Pattern CHAT_KEY = Pattern.compile(CHAT_KEY_REGEX);

    /**
     * 计算或取得本方法声明的结果，供当前Identifiers处理步骤使用。
     *
     * @param maxLength 当前Identifiers使用的最大长度，供其处理与状态记录使用。
     * @return 本次操作返回的校验模式结果。
     */
    private static Pattern opaque(int maxLength) {
        return Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maxLength - 1) + "}");
    }

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private Identifiers() {}
}
