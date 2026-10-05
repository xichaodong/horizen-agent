package dev.horizen.agent.application.turn;

import lombok.Value;

import reactor.core.publisher.Mono;

import java.util.List;

/** 跨实例取消传输接口；键和超时由具体传输实现管理。 */
public interface TurnControlChannel {
    /**
     * 取出待处理的执行控制通道。
     *
     * @param instanceId 当前服务实例标识，用于区分分布式执行与资源统计。
     * @param limit 本次处理或返回数量上限。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    Mono<List<CancelSignal>> drain(String instanceId, int limit);

    /**
     * 发送执行控制通道。
     *
     * @param executorId 持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param signal 当前执行控制通道持有的信号对象，供相应处理步骤使用。
     */
    void send(String executorId, CancelSignal signal);

    /** 跨实例传输的取消信号，定位需要停止的原执行。 */
    @Value
    class CancelSignal {
        /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
        String ownerKey;

        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        String sessionId;

        /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
        String turnId;
    }
}
