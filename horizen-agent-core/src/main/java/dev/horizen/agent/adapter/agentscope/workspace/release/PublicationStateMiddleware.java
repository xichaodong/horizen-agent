package dev.horizen.agent.adapter.agentscope.workspace.release;

import dev.horizen.agent.domain.workspace.policy.WorkspaceFilePolicy;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.*;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.*;

import reactor.core.publisher.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** 每次调用重建派生能力状态，保留对话和待处理操作。 */
public final class PublicationStateMiddleware implements MiddlewareBase {
    /** 沙箱执行的工作目录，发布文件投影与写入规则以此为基准。 */
    private final String sandboxRoot;

    /** 默认分组集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
    private final List<String> defaultGroups;

    /** 子级工具集合的索引映射，供按键查找或归并当前组件的数据。 */
    private final Map<String, Set<String>> childTools;

    /**
     * 创建发布工作状态中间件，初始化该组件所需的状态、配置或依赖。
     *
     * @param sandboxRoot 当前发布工作状态中间件使用的沙箱根，供其处理与状态记录使用。
     * @param defaultGroups 默认分组集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param childTools 子级工具集合的索引映射，供按键查找或归并当前组件的数据。
     */
    public PublicationStateMiddleware(
            String sandboxRoot, List<String> defaultGroups, Map<String, Set<String>> childTools) {
        this.sandboxRoot = sandboxRoot;
        this.defaultGroups = List.copyOf(defaultGroups);
        this.childTools = Map.copyOf(childTools);
    }

    /**
     * 返回本策略在中间件链中的执行顺序，供运行时排列处理步骤。
     *
     * @return 中间件执行顺序值。
     */
    @Override
    public int order() {
        return 1000;
    }

    /**
     * 响应系统Prompt。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param prompt 传给模型的提示文本，供当前模型请求使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        Seen seen = context.get(Seen.class);
        if (seen == null) {
            seen = new Seen();
            context.put(Seen.class, seen);
        }
        if (seen.agents.add(agent)) {
            List<String> groups =
                    agent instanceof ReActAgent react
                            ? defaultGroups.stream()
                                    .filter(name -> react.getToolkit().getToolGroup(name) != null)
                                    .toList()
                            : defaultGroups;
            var state = RuntimeContext.resolveAgentState(context, agent);
            if (state != null) {
                var tools = state.getToolContext();
                tools.setActivatedGroups(groups);
                tools.getSpawnRegistry().keySet().forEach(tools::removeSpawnEntry);
            }
            if (agent instanceof ReActAgent react) react.getToolkit().setActiveGroups(groups);
        }
        return Mono.just(prompt);
    }

    /**
     * 响应Acting。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input 本次处理的输入。
     * @param next 将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        Set<String> allowed = childTools.get(agent.getName());
        if (allowed != null
                && input.toolCalls().stream().anyMatch(call -> !allowed.contains(call.getName())))
            return Flux.error(
                    new IllegalStateException("Tool is outside the published subagent allowlist"));
        Set<String> mutations =
                Set.of(
                        "write_file",
                        "edit_file",
                        "delete_file",
                        "move_file",
                        "upload_file",
                        "patch");
        for (var call : input.toolCalls())
            if (mutations.contains(call.getName())) {
                for (var entry : call.getInput().entrySet()) {
                    if (Set.of("path", "from_path", "to_path", "source", "destination")
                                    .contains(entry.getKey())
                            && entry.getValue() instanceof String path
                            && protectedPath(path))
                        return Flux.error(
                                new IllegalStateException(
                                        "Published workspace content is read-only"));
                }
                if ("patch".equals(call.getName())
                        && call.getInput().get("patch") instanceof String patch) {
                    for (String line : patch.split("\\R")) {
                        for (String marker :
                                List.of(
                                        "*** Add File: ",
                                        "*** Update File: ",
                                        "*** Delete File: ",
                                        "*** Move to: "))
                            if (line.startsWith(marker)
                                    && protectedPath(line.substring(marker.length())))
                                return Flux.error(
                                        new IllegalStateException(
                                                "Published workspace content is read-only"));
                    }
                }
            }
        return next.apply(input);
    }

    /**
     * 响应模型推理。
     *
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input 本次处理的输入。
     * @param next 将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        Set<String> allowed = childTools.get(agent.getName());
        return allowed == null
                ? next.apply(input)
                : next.apply(
                        new ReasoningInput(
                                input.messages(),
                                input.tools().stream()
                                        .filter(tool -> allowed.contains(tool.getName()))
                                        .toList(),
                                input.options()));
    }

    /**
     * 检查protectedPath对应的条件，供调用方选择后续处理分支。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private boolean protectedPath(String value) {
        String p = value.replace('\\', '/');
        if (Arrays.asList(p.split("/")).contains("..")) return true;
        if (sandboxRoot != null && p.startsWith(sandboxRoot + "/"))
            p = p.substring(sandboxRoot.length() + 1);
        while (p.startsWith("./") || p.startsWith("/"))
            p = p.startsWith("./") ? p.substring(2) : p.substring(1);
        p = Path.of(p.isEmpty() ? "." : p).normalize().toString().replace('\\', '/');
        String normalized = p;
        return WorkspaceFilePolicy.serverOwned(normalized);
    }

    /** 发布工作状态中间件内部的Seen，封装该步骤需要的状态或输入输出。 */
    private static final class Seen {
        /** Agent集合的去重集合，供成员查找或范围检查使用。 */
        final Set<Agent> agents =
                Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    }
}
