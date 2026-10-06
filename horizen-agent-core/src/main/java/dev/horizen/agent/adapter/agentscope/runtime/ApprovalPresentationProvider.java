package dev.horizen.agent.adapter.agentscope.runtime;

import dev.horizen.agent.runtime.api.ApprovalPresentation;

import io.agentscope.core.agent.RuntimeContext;

import java.util.Map;

/**
 * 可选的工具展示数据扩展，仅在 AgentScope 请求确认后调用。实现必须无副作用，不能执行待审批操作。
 */
public interface ApprovalPresentationProvider {
    /**
     * 计算或取得本方法声明的结果，供当前ApprovalPresentationProvider处理步骤使用。
     *
     * @param input   本次处理的输入。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的审批呈现结果。
     */
    ApprovalPresentation describeApproval(Map<String, Object> input, RuntimeContext context);
}
