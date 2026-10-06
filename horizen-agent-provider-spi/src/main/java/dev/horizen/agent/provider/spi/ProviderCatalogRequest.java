package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册发现请求，或按所有者隔离的 Turn 工具目录请求。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderCatalogRequest {
    /**
     * 协议的版本，供兼容或并发检查使用。
     */
    private int protocolVersion = ProviderProtocol.CURRENT_VERSION;

    /**
     * 本组件使用的 {@code CatalogScope} 状态或依赖，用于 scope 的处理。
     */
    private CatalogScope scope;

    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey = "";

    /**
     * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     */
    private String sessionId = "";

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId = "";

    /**
     * 校验当前提供方目录请求的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的提供方目录请求结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ProviderCatalogRequest validate() {
        requireVersion(protocolVersion);
        if (scope == null) throw new IllegalArgumentException("scope is required");
        ownerKey = text(ownerKey);
        sessionId = text(sessionId);
        turnId = text(turnId);
        if (scope == CatalogScope.TURN
                && (ownerKey.isBlank() || sessionId.isBlank() || turnId.isBlank())) {
            throw new IllegalArgumentException(
                    "Turn catalog requires ownerKey, sessionId and turnId");
        }
        return this;
    }

    /**
     * 取得并校验版本。
     *
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static void requireVersion(int version) {
        if (version != ProviderProtocol.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported protocolVersion: " + version);
        }
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
