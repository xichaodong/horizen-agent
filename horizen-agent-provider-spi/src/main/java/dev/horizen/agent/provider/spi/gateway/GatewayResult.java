package dev.horizen.agent.provider.spi.gateway;

import dev.horizen.agent.provider.spi.ProviderResultStatus;

import lombok.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 类型化执行结果与 Provider 的 JSON 兼容结果信封。
 */
@Value
public class GatewayResult {
    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    ProviderResultStatus status;

    /**
     * 当前事件的结构化负载，与定位标识和事件类别分开保存。
     */
    Map<String, Object> payload;

    /**
     * 创建网关结果，初始化该组件所需的状态、配置或依赖。
     *
     * @param status  当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param payload 负载的索引映射，供按键查找或归并当前组件的数据。
     */
    public GatewayResult(ProviderResultStatus status, Map<String, Object> payload) {
        this.status = Objects.requireNonNull(status);
        this.payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
