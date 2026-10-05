package dev.horizen.agent.common.digest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 仅提供摘要计算；规范化、前缀和身份规则由调用者决定。 */
public final class DigestUtils {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private DigestUtils() {}

    /**
     * 生成当前操作所需的sha256Hex文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成当前操作所需的sha256Hex文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    public static String sha256Hex(byte[] value) {
        return HexFormat.of().formatHex(sha256().digest(value));
    }

    /**
     * 计算或取得本方法声明的结果，供当前DigestUtils处理步骤使用。
     *
     * @return 本次操作返回的消息摘要结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static MessageDigest sha256() {
        try {
            return newSha256();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** 创建新的增量摘要计算器，用于流式输入或规范化输入。 */
    public static MessageDigest newSha256() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }
}
