package dev.horizen.agent.web.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 共用 Bearer 解析和恒定时间令牌比较；项目授权单独处理。
 */
public final class ServiceTokenVerifier {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ServiceTokenVerifier() {
    }

    /**
     * 检查是否匹配服务令牌Verifier。
     *
     * @param authorization 当前服务令牌Verifier使用的授权，供其处理与状态记录使用。
     * @param expected      当前服务令牌Verifier使用的预期，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public static boolean matches(String authorization, String expected) {
        if (expected == null || expected.isBlank()) return false;
        String supplied =
                authorization != null && authorization.startsWith("Bearer ")
                        ? authorization.substring(7)
                        : "";
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
