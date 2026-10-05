package dev.horizen.agent.tool.adapter;

/**
 * 兼容早期嵌入 Core 的适配器。新的应用适配器应实现
 * {@code dev.horizen.agent.provider.spi.ToolProviderSpi} 或其 JSON 传输协议，再由传输层映射到
 * {@link ToolProvider}。
 *
 * <p>适配器接收宿主构建的可信上下文；工具参数仅包含模型可见的业务输入。实现不能从 {@code input}
 * 接受身份、租户或授权字段。
 */
@Deprecated
public interface ToolAdapter extends ToolProvider {}
