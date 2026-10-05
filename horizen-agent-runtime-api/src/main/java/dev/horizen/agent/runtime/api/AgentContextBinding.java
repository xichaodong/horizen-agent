package dev.horizen.agent.runtime.api;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.beans.ConstructorProperties;
import java.util.Objects;

/** 宿主为单次运行提供的可信上下文值，供工具和中间件按类型读取。 */
@Getter
@EqualsAndHashCode
public class AgentContextBinding<T> {
    /** 本对象的协议类别，用于选择对应的解析或呈现规则。 */
    private final Class<T> type;

    /** 当前绑定或协议值，解释方式由对应类型决定。 */
    private final T value;

    /**
     * 创建Agent上下文绑定，初始化该组件所需的状态、配置或依赖。
     *
     * @param type 当前操作使用的目标类型或类别。
     * @param value 待校验、转换或保存的原始值。
     */
    @ConstructorProperties({"type", "value"})
    public AgentContextBinding(Class<T> type, T value) {
        this.type = Objects.requireNonNull(type, "type");
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * 生成只包含必要摘要的诊断文本，避免直接输出绑定内容或消息正文。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String toString() {
        return "AgentContextBinding[type=" + type.getName() + ", value=[bound]]";
    }
}
