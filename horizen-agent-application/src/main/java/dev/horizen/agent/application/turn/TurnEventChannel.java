package dev.horizen.agent.application.turn;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import reactor.core.publisher.Flux;

/** 容量受限的事件发布与回放接口，具体存储传输由适配器实现。 */
public interface TurnEventChannel {
    /**
     * 发布执行事件通道。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param event 当前执行事件通道持有的事件对象，供相应处理步骤使用。
     * @return 本次操作返回的长整型结果。
     */
    long publish(String ownerKey, AgentRuntimeEvent event);

    /**
     * 发布执行事件通道。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param event 当前执行事件通道持有的事件对象，供相应处理步骤使用。
     * @param timelineSequence 当前执行事件通道使用的时间线序号，供其处理与状态记录使用。
     * @return 本次操作返回的长整型结果。
     */
    long publish(String ownerKey, AgentRuntimeEvent event, long timelineSequence);

    /**
     * 计算或取得本方法声明的结果，供当前TurnEventChannel处理步骤使用。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param afterEventSequence 客户端已经观察到的执行事件游标，重连时从其后继续回放。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    Flux<AgentRuntimeEvent> replay(String ownerKey, String turnId, long afterEventSequence);

    /**
     * 裁剪执行事件通道。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    void trim(String ownerKey, String turnId);

    /** 事件写入异常异常，明确当前流程不能继续或需要由调用方选择恢复路径。 */
    class EventWriteException extends IllegalStateException {
        /**
         * 创建事件写入异常，初始化该组件所需的状态、配置或依赖。
         *
         * @param cause 导致当前失败的原始异常或原因，供错误传播与诊断使用。
         */
        public EventWriteException(Throwable cause) {
            super(cause);
        }

        /**
         * 创建事件写入异常，初始化该组件所需的状态、配置或依赖。
         *
         * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
         * @param cause 导致当前失败的原始异常或原因，供错误传播与诊断使用。
         */
        public EventWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
