package dev.horizen.agent.adapter.agentscope.runtime;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

/**
 * 共享执行身份；各事件类型通过具名字段提供自身事实。
 */
final class RuntimeEventFactory {
    /**
     * 计算或取得本方法声明的结果，供当前RuntimeEventFactory处理步骤使用。
     *
     * @param request 当前操作的请求参数。
     * @param type    当前操作使用的目标类型或类别。
     * @param id      目标对象的标识。
     * @return 本次操作返回的Agent运行时事件构造器结果。
     */
    static AgentRuntimeEvent.AgentRuntimeEventBuilder event(
            AgentTurnRequest request, AgentRuntimeEvent.Type type, String id) {
        return AgentRuntimeEvent.builder()
                .type(type)
                .turnId(request.getTurnId())
                .sessionId(request.getSessionId())
                .id(id);
    }
}
