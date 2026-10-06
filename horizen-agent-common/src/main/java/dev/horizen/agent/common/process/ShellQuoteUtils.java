package dev.horizen.agent.common.process;

/**
 * 为单个 POSIX Shell 参数添加转义引号，不负责命令授权或校验。
 */
public final class ShellQuoteUtils {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ShellQuoteUtils() {
    }

    /**
     * 转义shellQuote工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
