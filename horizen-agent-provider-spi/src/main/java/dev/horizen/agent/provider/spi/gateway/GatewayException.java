package dev.horizen.agent.provider.spi.gateway;

import lombok.Getter;

/** 安全的传输失败，在各运行时适配器间保留 Provider 错误信封。 */
@Getter
public final class GatewayException extends RuntimeException {
    /** 当前执行或查询的结果，供后续状态转换或协议输出使用。 */
    private final GatewayResult result;

    /**
     * 创建网关异常，初始化该组件所需的状态、配置或依赖。
     *
     * @param result 本次处理已有的结果。
     */
    public GatewayException(GatewayResult result) {
        super("Gateway request failed");
        this.result = result;
    }
}
