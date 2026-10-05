package dev.horizen.agent.domain.artifact;

import java.util.Locale;
import java.util.Set;

/** 发送给模型的图像共用的允许列表、字节限制和文件签名校验。 */
public final class ArtifactImagePolicy {
    /** 图片TYPES的固定取值，用于相应策略和边界判断。 */
    private static final Set<String> IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private ArtifactImagePolicy() {}

    /**
     * 判断支持。
     *
     * @param mediaType 当前产物图片策略使用的媒体类型，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public static boolean isSupported(String mediaType) {
        return mediaType != null && IMAGE_TYPES.contains(mediaType.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 取得并校验支持媒体类型。
     *
     * @param mediaType 当前产物图片策略使用的媒体类型，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String requireSupportedMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        if (!isSupported(normalized)) {
            throw new IllegalArgumentException("unsupported image media type: " + normalized);
        }
        return normalized;
    }

    /**
     * 校验字节。
     *
     * @param mediaType 当前产物图片策略使用的媒体类型，供其处理与状态记录使用。
     * @param value 待校验、转换或保存的原始值。
     * @param maxBytes 本次处理或传输允许的最大字节数。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void validateBytes(String mediaType, byte[] value, long maxBytes) {
        String normalized = requireSupportedMediaType(mediaType);
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("image content is empty");
        }
        if (value.length > maxBytes) throw new IllegalArgumentException("image exceeds byte limit");
        boolean valid =
                switch (normalized) {
                    case "image/png" ->
                            starts(value, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
                    case "image/jpeg" -> starts(value, 0xff, 0xd8, 0xff);
                    case "image/gif" -> starts(value, 0x47, 0x49, 0x46, 0x38);
                    case "image/webp" ->
                            starts(value, 0x52, 0x49, 0x46, 0x46)
                                    && value.length >= 12
                                    && value[8] == 'W'
                                    && value[9] == 'E'
                                    && value[10] == 'B'
                                    && value[11] == 'P';
                    default -> false;
                };
        if (!valid) throw new IllegalArgumentException("image bytes do not match media type");
    }

    /**
     * 检查starts对应的条件，供调用方选择后续处理分支。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param prefix 当前产物图片策略持有的前缀对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean starts(byte[] value, int... prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if ((value[index] & 0xff) != prefix[index]) return false;
        }
        return true;
    }
}
