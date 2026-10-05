package dev.horizen.agent.tool.adapter;

import io.agentscope.core.agent.RuntimeContext;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 每个 RuntimeContext Turn 最多解析一次适配器目录。 */
public final class ToolCatalogResolver {
    /** 把外部 Provider 契约连接到当前运行时的调用适配器。 */
    private final ToolProvider adapter;

    /** 适配器的标识，用于关联相应记录或执行。 */
    private final String adapterId;

    /** 时间来源，用于计算更新时间、过期时间或执行时限。 */
    private final Clock clock;

    /**
     * 创建工具目录解析器，初始化该组件所需的状态、配置或依赖。
     *
     * @param adapter 当前工具目录解析器持有的适配器对象，供相应处理步骤使用。
     */
    public ToolCatalogResolver(ToolProvider adapter) {
        this(adapter, adapter.getClass().getName(), Clock.systemUTC());
    }

    /**
     * 创建工具目录解析器，初始化该组件所需的状态、配置或依赖。
     *
     * @param adapter 当前工具目录解析器持有的适配器对象，供相应处理步骤使用。
     * @param adapterId 适配器的标识，用于关联相应记录或执行。
     * @param clock 时间来源，用于计算更新时间、过期时间或执行时限。
     */
    ToolCatalogResolver(ToolProvider adapter, String adapterId, Clock clock) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 解析工具目录解析器。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Mono<ToolCatalogSnapshot> resolve(ToolAdapterContext context) {
        Objects.requireNonNull(context, "context");
        RuntimeContext runtime = context.getRuntimeContext();
        if (context.getTurnId() == null || context.getTurnId().isBlank()) {
            return adapter.list(context)
                    .map(ToolCatalogResolver::validate)
                    .map(
                            definitions ->
                                    new ToolCatalogSnapshot(
                                            adapterId,
                                            "direct-" + UUID.randomUUID(),
                                            Instant.now(clock),
                                            definitions));
        }
        ToolCatalogSnapshot cached = runtime.get(adapterId, ToolCatalogSnapshot.class);
        if (cached != null && cached.getTurnId().equals(context.getTurnId()))
            return Mono.just(cached);
        return adapter.list(context)
                .map(definitions -> validate(definitions))
                .map(
                        definitions -> {
                            ToolCatalogSnapshot snapshot =
                                    new ToolCatalogSnapshot(
                                            adapterId,
                                            context.getTurnId(),
                                            Instant.now(clock),
                                            definitions);
                            runtime.put(adapterId, ToolCatalogSnapshot.class, snapshot);
                            return snapshot;
                        });
    }

    /**
     * 校验当前工具目录解析器的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @param definitions definitions的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static List<ToolDefinition> validate(List<ToolDefinition> definitions) {
        if (definitions == null)
            throw new IllegalArgumentException("adapter returned null catalog");
        HashSet<String> names = new HashSet<>();
        for (ToolDefinition definition : definitions) {
            if (definition == null || !names.add(definition.getName())) {
                throw new IllegalArgumentException(
                        "adapter catalog contains duplicate or null tool");
            }
        }
        return List.copyOf(definitions);
    }
}
