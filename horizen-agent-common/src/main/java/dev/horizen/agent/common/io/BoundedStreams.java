package dev.horizen.agent.common.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 读取时限制字节数；流的所有权和截止时间仍由调用者管理。 */
public final class BoundedStreams {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private BoundedStreams() {}

    /**
     * 读取有界Streams。
     *
     * @param input 本次处理的输入。
     * @param maximumBytes 最大的字节数，用于容量或传输限制。
     * @return 本次处理取得或生成的内容字节。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static byte[] read(InputStream input, long maximumBytes) throws IOException {
        if (maximumBytes < 0 || maximumBytes > Integer.MAX_VALUE - 8L)
            throw new IllegalArgumentException("Invalid byte read limit");
        ByteArrayOutputStream output =
                new ByteArrayOutputStream((int) Math.min(maximumBytes, 65536));
        byte[] buffer = new byte[65536];
        long count = 0;
        int size;
        while ((size = input.read(buffer)) != -1) {
            count += size;
            if (count > maximumBytes) throw new IOException("Content exceeds read limit");
            output.write(buffer, 0, size);
        }
        return output.toByteArray();
    }
}
