package dev.horizen.agent.tool.adapter;

import dev.horizen.agent.adapter.agentscope.presentation.PresentationToolResultMapper;
import dev.horizen.agent.domain.presentation.PresentationEventCollector;
import dev.horizen.agent.tool.governance.ToolViewSnapshot;

import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 将适配器快照转换为模型可直接调用的 AgentScope 工具。 */
public final class ToolAdapterTools {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private ToolAdapterTools() {}

    /**
     * 注册工具适配器工具集合。
     *
     * @param toolkit 当前工具适配器工具集合持有的工具集对象，供相应处理步骤使用。
     * @param adapter 当前工具适配器工具集合持有的适配器对象，供相应处理步骤使用。
     * @param definitions definitions的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次操作返回的工具目录解析器结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static ToolCatalogResolver register(
            Toolkit toolkit, ToolProvider adapter, List<ToolDefinition> definitions) {
        Objects.requireNonNull(toolkit, "toolkit");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(definitions, "definitions");
        Set<String> names = new HashSet<>();
        ToolCatalogResolver resolver = new ToolCatalogResolver(adapter);
        for (ToolDefinition definition : definitions) {
            if (!names.add(definition.getName())) {
                throw new IllegalArgumentException(
                        "duplicate adapter tool: " + definition.getName());
            }
            if (toolkit.getTool(definition.getName()) != null) {
                throw new IllegalArgumentException(
                        "adapter tool conflicts with existing tool: " + definition.getName());
            }
            toolkit.registerAgentTool(new AdapterTool(adapter, resolver, definition));
        }
        return resolver;
    }

    /** 将 Provider SPI 工具定义与调用结果接入 AgentScope 工具循环。 */
    private static final class AdapterTool extends ToolBase {
        /** 把外部 Provider 契约连接到当前运行时的调用适配器。 */
        private final ToolProvider adapter;

        /** 按当前版本和访问范围解析工具目录的组件。 */
        private final ToolCatalogResolver resolver;

        /** 当前工具的 Schema、治理属性与执行元数据。 */
        private final ToolDefinition definition;

        /**
         * 创建适配器工具，初始化该组件所需的状态、配置或依赖。
         *
         * @param adapter 当前适配器工具持有的适配器对象，供相应处理步骤使用。
         * @param resolver 提供解析器能力的依赖，具体实现由当前组件的组装方传入。
         * @param definition 当前适配器工具持有的定义对象，供相应处理步骤使用。
         */
        private AdapterTool(
                ToolProvider adapter, ToolCatalogResolver resolver, ToolDefinition definition) {
            super(
                    ToolBase.builder()
                            .name(definition.getName())
                            .description(definition.getDescription())
                            .inputSchema(definition.getInputSchema())
                            .readOnly(definition.isReadOnly())
                            .concurrencySafe(definition.isConcurrencySafe()));
            this.adapter = adapter;
            this.resolver = resolver;
            this.definition = definition;
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
            return definition.requiresApproval()
                    ? Mono.just(PermissionDecision.ask("业务工具要求审批：" + definition.getName()))
                    : super.checkPermissions(input, context);
        }

        /**
         * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
         *
         * @param param 当前适配器工具持有的参数对象，供相应处理步骤使用。
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            if (param.getRuntimeContext() == null) {
                return Mono.just(ToolResultBlock.error("missing_tool_adapter_context"));
            }
            ToolViewSnapshot view = param.getRuntimeContext().get(ToolViewSnapshot.class);
            if (view != null && !view.allows(definition.getName())) {
                return Mono.just(ToolResultBlock.error("tool_not_authorized_in_current_view"));
            }
            String toolCallId =
                    param.getToolUseBlock() == null ? "" : param.getToolUseBlock().getId();
            ToolAdapterContext context = ToolAdapterContext.from(param.getRuntimeContext());
            return resolver.resolve(context)
                    .flatMap(
                            snapshot -> {
                                ToolDefinition current =
                                        snapshot.getDefinitions().stream()
                                                .filter(
                                                        value ->
                                                                value.getName()
                                                                        .equals(
                                                                                definition
                                                                                        .getName()))
                                                .findFirst()
                                                .orElse(null);
                                if (current == null)
                                    return Mono.just(
                                            ToolResultBlock.error("tool_authorization_revoked"));
                                if (!definition.equals(current)) {
                                    return Mono.just(
                                            ToolResultBlock.error(
                                                    "tool_contract_changed_refresh_registration_required"));
                                }
                                Map<String, Object> input =
                                        param.getInput() == null ? Map.of() : param.getInput();
                                return Mono.deferContextual(
                                        subscriber ->
                                                Mono.defer(
                                                                () ->
                                                                        adapter.invoke(
                                                                                context,
                                                                                toolCallId,
                                                                                definition
                                                                                        .getName(),
                                                                                input))
                                                        .doOnNext(
                                                                result -> {
                                                                    PresentationEventCollector
                                                                            collector =
                                                                                    param.getRuntimeContext()
                                                                                            .get(
                                                                                                    PresentationEventCollector
                                                                                                            .class);
                                                                    if (collector == null) return;
                                                                    for (var block :
                                                                            PresentationToolResultMapper
                                                                                    .extract(
                                                                                            result,
                                                                                            toolCallId,
                                                                                            () ->
                                                                                                    AgentEventEmitter
                                                                                                            .fromForwardingContext(
                                                                                                                    subscriber)
                                                                                                            .or(
                                                                                                                    () ->
                                                                                                                            AgentEventEmitter
                                                                                                                                    .fromContext(
                                                                                                                                            subscriber))
                                                                                                            .ifPresent(
                                                                                                                    emitter ->
                                                                                                                            emitter
                                                                                                                                    .emit(
                                                                                                                                            new CustomEvent(
                                                                                                                                                    "horizen.execution_notice",
                                                                                                                                                    Map
                                                                                                                                                            .of(
                                                                                                                                                                    "errorCode",
                                                                                                                                                                    "PRESENTATION_FAILED",
                                                                                                                                                                    "toolCallId",
                                                                                                                                                                    toolCallId,
                                                                                                                                                                    "toolName",
                                                                                                                                                                    definition
                                                                                                                                                                            .getName())))))) {
                                                                        collector.record(
                                                                                toolCallId, block);
                                                                    }
                                                                }));
                            });
        }
    }
}
