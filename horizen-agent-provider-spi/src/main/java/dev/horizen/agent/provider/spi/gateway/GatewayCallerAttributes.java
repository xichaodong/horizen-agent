package dev.horizen.agent.provider.spi.gateway;

import lombok.Getter;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 宿主提供的不透明业务上下文，由 Provider 解释，运行时不解读。 */
@Getter
public final class GatewayCallerAttributes {
    /** 值集合的索引映射，供按键查找或归并当前组件的数据。 */
    private final Map<String, String> values;

    /**
     * 创建网关CallerAttributes，初始化该组件所需的状态、配置或依赖。
     *
     * @param values 本次批量处理的值集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public GatewayCallerAttributes(Map<String, String> values) {
        this.values = Map.copyOf(values == null ? Map.of() : values);
        if (this.values.size() > 16)
            throw new IllegalArgumentException("Too many Provider context attributes");
        this.values.forEach(
                (key, value) -> {
                    if (!key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                            || value.isBlank()
                            || value.length() > 256
                            || Set.of(
                                            "authorization",
                                            "token",
                                            "accesstoken",
                                            "password",
                                            "secret",
                                            "accesskey",
                                            "secretkey")
                                    .contains(
                                            key.replace("_", "")
                                                    .replace("-", "")
                                                    .toLowerCase(Locale.ROOT)))
                        throw new IllegalArgumentException("Invalid Provider context attribute");
                });
    }

    /**
     * 生成只包含必要摘要的诊断文本，避免直接输出绑定内容或消息正文。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String toString() {
        return "GatewayCallerAttributes[redacted]";
    }
}
