package dev.horizen.agent.observability.horizen;

import dev.horizen.agent.observability.ExecutionTraceContext;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 为每个并发委派创建独立上下文，不改变内置权限。 */
final class HorizenDelegationTool extends ToolBase {
    /** 被包装的原始实现，由本组件补充隔离、观测或恢复行为。 */
    private final ToolBase delegate;

    /**
     * 创建Horizen委派工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param delegate 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    HorizenDelegationTool(ToolBase delegate) {
        super(
                delegate.getName(),
                delegate.getDescription(),
                delegate.getParameters(),
                delegate.isReadOnly(),
                delegate.isConcurrencySafe(),
                delegate.isMcp(),
                delegate.getMcpName(),
                delegate.isExternalTool(),
                delegate.isStateInjected());
        this.delegate = delegate;
    }

    /**
     * 读取严格。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    /**
     * 读取输出Schema。
     *
     * @return 按返回类型约定组织的结果映射。
     */
    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    /**
     * 检查权限集合。
     *
     * @param input 本次处理的输入。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input, PermissionContextState context) {
        return delegate.checkPermissions(input, context);
    }

    /**
     * 检查matchRule对应的条件，供调用方选择后续处理分支。
     *
     * @param rule 当前Horizen委派工具使用的规则，供其处理与状态记录使用。
     * @param input 本次处理的输入。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean matchRule(String rule, Map<String, Object> input) {
        return delegate.matchRule(rule, input);
    }

    /**
     * 计算或取得本方法声明的结果，供当前HorizenDelegationTool处理步骤使用。
     *
     * @param input 本次处理的输入。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> input) {
        return delegate.generateSuggestions(input);
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前Horizen委派工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext context = param.getRuntimeContext();
        ExecutionTraceContext trace =
                context == null ? null : context.get(ExecutionTraceContext.class);
        if (trace == null) return delegate.callAsync(param);
        String callId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
        RuntimeContext child =
                RuntimeContext.builder(context)
                        .put(
                                HorizenTraceParent.class,
                                new HorizenTraceParent(trace.getTraceId(), trace.toolSpan(callId)))
                        .build();
        return delegate.callAsync(
                ToolCallParam.builder()
                        .toolUseBlock(param.getToolUseBlock())
                        .input(param.getInput())
                        .agent(param.getAgent())
                        .emitter(param.getEmitter())
                        .runtimeContext(child)
                        .build());
    }

    /**
     * 安装Horizen委派工具。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     */
    static void install(ReActAgent agent) {
        var toolkit = agent.getToolkit();
        synchronized (toolkit) {
            for (String name : List.of("agent_spawn", "agent_send")) {
                AgentTool tool = toolkit.getTool(name);
                if (tool instanceof ToolBase base && !(tool instanceof HorizenDelegationTool)) {
                    toolkit.registerAgentTool(new HorizenDelegationTool(base));
                }
            }
        }
    }
}
