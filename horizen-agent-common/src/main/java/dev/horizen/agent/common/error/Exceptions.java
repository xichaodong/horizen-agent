package dev.horizen.agent.common.error;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** 不依赖框架的异常原因检查，可处理格式错误的循环原因链。 */
public final class Exceptions {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private Exceptions() {}

    /**
     * 计算或取得本方法声明的结果，供当前Exceptions处理步骤使用。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次操作返回的Throwable结果。
     */
    public static Throwable rootCause(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error");
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(current);
        while (current.getCause() != null && visited.add(current.getCause()))
            current = current.getCause();
        return current;
    }
}
