package dev.horizen.agent.adapter.agentscope.runtime;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;

import lombok.Builder;
import lombok.Value;

import java.util.*;

/**
 * 运行时事件映射上下文类型，承载本模块对外或内部协作所需的状态与契约。
 */
@Value
@Builder
class RuntimeEventMappingContext {
    /**
     * 本组件使用的 {@code AgentTurnRequest} 状态或依赖，用于 request 的处理。
     */
    AgentTurnRequest request;

    /**
     * 执行开始的时间，用于记录对应生命周期节点。
     */
    long turnStartedAt;

    /**
     * 步骤启动次数的索引映射，供按键查找或归并当前组件的数据。
     */
    Map<String, Long> stepStarts;

    /**
     * AgentScope 当前调用上下文，携带隔离身份和类型化宿主绑定。
     */
    RuntimeContext runtimeContext;

    /**
     * notices的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    List<AgentRuntimeEvent> notices;

    /**
     * 当前配置的 Agent 实例，承担模型与工具循环执行。
     */
    HarnessAgent agent;
}
