package dev.horizen.agent.provider.spi;

/**
 * Horizen 和外部 Tool Provider 适配器共用的传输协议常量。
 */
public final class ProviderProtocol {
    /**
     * 当前版本的固定取值，用于相应策略和边界判断。
     */
    public static final int CURRENT_VERSION = 1;

    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ProviderProtocol() {
    }
}
