package dev.horizen.agent.provider.spi.gateway;

import lombok.Getter;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 可信传输坐标、调用者属性和单次调用的追踪请求头。
 */
@Getter
public final class GatewayContext {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private final String ownerKey;

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private final String sessionId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private final String turnId;

    /**
     * 注册的状态标记，用于选择当前组件的处理路径。
     */
    private final boolean registration;

    /**
     * 可信宿主提供的调用属性，不从模型输入中推断业务身份。
     */
    private final Map<String, String> callerAttributes;

    /**
     * Trace请求头的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Function<String, Map<String, String>> traceHeaders;

    /**
     * 创建网关上下文，初始化该组件所需的状态、配置或依赖。
     *
     * @param owner        当前网关上下文使用的数据归属，供其处理与状态记录使用。
     * @param session      当前网关上下文使用的会话，供其处理与状态记录使用。
     * @param turn         当前网关上下文使用的执行，供其处理与状态记录使用。
     * @param registration 注册的状态标记，用于选择当前组件的处理路径。
     */
    public GatewayContext(String owner, String session, String turn, boolean registration) {
        this(owner, session, turn, registration, Map.of());
    }

    /**
     * 创建网关上下文，初始化该组件所需的状态、配置或依赖。
     *
     * @param owner        当前网关上下文使用的数据归属，供其处理与状态记录使用。
     * @param session      当前网关上下文使用的会话，供其处理与状态记录使用。
     * @param turn         当前网关上下文使用的执行，供其处理与状态记录使用。
     * @param registration 注册的状态标记，用于选择当前组件的处理路径。
     * @param attributes   当前事件或对象携带的扩展属性，按所属协议解释。
     */
    public GatewayContext(
            String owner,
            String session,
            String turn,
            boolean registration,
            Map<String, String> attributes) {
        this(owner, session, turn, registration, attributes, id -> Map.of());
    }

    /**
     * 创建网关上下文，初始化该组件所需的状态、配置或依赖。
     *
     * @param owner        当前网关上下文使用的数据归属，供其处理与状态记录使用。
     * @param session      当前网关上下文使用的会话，供其处理与状态记录使用。
     * @param turn         当前网关上下文使用的执行，供其处理与状态记录使用。
     * @param registration 注册的状态标记，用于选择当前组件的处理路径。
     * @param attributes   当前事件或对象携带的扩展属性，按所属协议解释。
     * @param traceHeaders Trace请求头的索引映射，供按键查找或归并当前组件的数据。
     */
    public GatewayContext(
            String owner,
            String session,
            String turn,
            boolean registration,
            Map<String, String> attributes,
            Function<String, Map<String, String>> traceHeaders) {
        this.ownerKey = text(owner);
        this.sessionId = text(session);
        this.turnId = text(turn);
        this.registration = registration;
        this.callerAttributes = new GatewayCallerAttributes(attributes).getValues();
        this.traceHeaders = Objects.requireNonNull(traceHeaders);
    }

    /**
     * 读取Trace请求头的当前值。
     *
     * @param id 目标对象的标识。
     * @return {@link #traceHeaders} 中保存的值。
     */
    public Map<String, String> traceHeaders(String id) {
        return traceHeaders.apply(id);
    }

    /**
     * 判断是否存在调用作用域。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean hasInvocationScope() {
        return !registration && !ownerKey.isBlank() && !sessionId.isBlank() && !turnId.isBlank();
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 生成只包含必要摘要的诊断文本，避免直接输出绑定内容或消息正文。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String toString() {
        return "GatewayContext[registration=" + registration + ", identity=[redacted]]";
    }
}
