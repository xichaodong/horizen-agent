package dev.horizen.agent.tool.governance;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.tool.adapter.ToolAdapterContext;
import dev.horizen.agent.tool.adapter.ToolCatalogResolver;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 为每次模型推理捕获经过工具分组过滤后的准确工具集合。
 */
public final class ToolViewMiddleware implements MiddlewareBase {
    /**
     * 当前组件的诊断日志器。
     */
    private static final Logger log = LoggerFactory.getLogger(ToolViewMiddleware.class);

    /**
     * 按工具名维护执行元数据与可用状态的注册表。
     */
    private final ToolDescriptorRegistry registry;

    /**
     * 适配器Resolvers的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    private final List<ToolCatalogResolver> adapterResolvers;

    /**
     * 创建工具视图中间件，初始化该组件所需的状态、配置或依赖。
     */
    public ToolViewMiddleware() {
        this(null, List.of());
    }

    /**
     * 创建工具视图中间件，初始化该组件所需的状态、配置或依赖。
     *
     * @param registry 当前工具视图中间件持有的注册表对象，供相应处理步骤使用。
     */
    public ToolViewMiddleware(ToolDescriptorRegistry registry) {
        this(registry, List.of());
    }

    /**
     * 创建工具视图中间件，初始化该组件所需的状态、配置或依赖。
     *
     * @param registry         当前工具视图中间件持有的注册表对象，供相应处理步骤使用。
     * @param adapterResolvers 适配器Resolvers的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    public ToolViewMiddleware(
            ToolDescriptorRegistry registry, List<ToolCatalogResolver> adapterResolvers) {
        this.registry = registry;
        this.adapterResolvers = List.copyOf(adapterResolvers);
    }

    /**
     * 响应Agent。
     *
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input   本次处理的输入。
     * @param next    将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (adapterResolvers.isEmpty()) return next.apply(input);
        return Flux.deferContextual(
                subscriber ->
                        Flux.fromIterable(adapterResolvers)
                                .flatMap(
                                        resolver ->
                                                resolver.resolve(ToolAdapterContext.from(context)))
                                .flatMapIterable(snapshot -> snapshot.getDefinitions())
                                .map(definition -> definition.getName())
                                .collectList()
                                .doOnNext(
                                        names ->
                                                context.put(
                                                        AdapterToolAuthorization.class,
                                                        new AdapterToolAuthorization(
                                                                new LinkedHashSet<>(names))))
                                .onErrorResume(
                                        error -> {
                                            log.warn(
                                                    "Adapter catalog resolution failed; hiding business tools",
                                                    error);
                                            context.put(
                                                    AdapterToolAuthorization.class,
                                                    new AdapterToolAuthorization(Set.of()));
                                            AgentEventEmitter.fromForwardingContext(subscriber)
                                                    .or(
                                                            () ->
                                                                    AgentEventEmitter.fromContext(
                                                                            subscriber))
                                                    .ifPresent(
                                                            emitter ->
                                                                    emitter.emit(
                                                                            new CustomEvent(
                                                                                    "horizen.execution_notice",
                                                                                    Map.of(
                                                                                            "errorCode",
                                                                                            "TOOL_CATALOG_FAILED"))));
                                            return Mono.empty();
                                        })
                                .thenMany(Flux.defer(() -> next.apply(input))));
    }

    /**
     * 响应模型推理。
     *
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input   本次处理的输入。
     * @param next    将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        AdapterToolAuthorization business = context.get(AdapterToolAuthorization.class);
        List<ToolSchema> visible =
                input.tools().stream()
                        .filter(
                                schema ->
                                        registry == null || registry.isAvailable(schema.getName()))
                        .filter(
                                schema ->
                                        registry == null
                                                || !registry.isProviderTool(schema.getName())
                                                || (business != null
                                                && business.allows(schema.getName())))
                        .toList();
        for (ToolSchema schema : visible) names.add(schema.getName());
        ToolInvocationScope scope = context.get(ToolInvocationScope.class);
        String turnId = scope == null ? "" : scope.getTurnId();
        ToolViewSnapshot snapshot =
                new ToolViewSnapshot(
                        context.getUserId(),
                        context.getSessionId(),
                        turnId,
                        version(names),
                        Instant.now(),
                        names);
        context.put(ToolViewSnapshot.class, snapshot);
        log.debug(
                "Resolved tool view [session={}, turn={}, version={}, count={}]",
                context.getSessionId(),
                turnId,
                snapshot.getVersion(),
                names.size());
        return next.apply(new ReasoningInput(input.messages(), visible, input.options()));
    }

    /**
     * 响应Acting。
     *
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param input   本次处理的输入。
     * @param next    将输入转换为目标结果的函数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        ToolViewSnapshot snapshot = context.get(ToolViewSnapshot.class);
        if (snapshot != null) {
            List<String> denied =
                    input.toolCalls().stream()
                            .map(call -> call.getName())
                            .filter(name -> !snapshot.allows(name))
                            .distinct()
                            .toList();
            if (!denied.isEmpty()) {
                log.warn(
                        "Rejected tools outside current view [session={}, tools={}]",
                        context.getSessionId(),
                        denied);
                return Flux.error(
                        new IllegalStateException(
                                "tool_not_authorized_in_current_view:" + String.join(",", denied)));
            }
        }
        return next.apply(input);
    }

    /**
     * 生成当前操作所需的version文本，供调用方继续处理。
     *
     * @param names 名称集合的去重集合，供成员查找或范围检查使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static String version(Set<String> names) {
        try {
            MessageDigest digest = DigestUtils.newSha256();
            names.stream()
                    .sorted()
                    .forEach(
                            name -> {
                                digest.update(name.getBytes(StandardCharsets.UTF_8));
                                digest.update((byte) 0);
                            });
            StringBuilder value = new StringBuilder("tools-");
            for (byte item : digest.digest()) value.append(String.format("%02x", item));
            return value.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
