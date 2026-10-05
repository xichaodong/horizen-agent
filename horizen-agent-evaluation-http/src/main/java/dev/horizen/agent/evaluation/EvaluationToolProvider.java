package dev.horizen.agent.evaluation;

import dev.horizen.agent.tool.adapter.ToolAdapterContext;
import dev.horizen.agent.tool.adapter.ToolCatalogSnapshot;
import dev.horizen.agent.tool.adapter.ToolDefinition;
import dev.horizen.agent.tool.adapter.ToolProvider;

import io.agentscope.core.message.ToolResultBlock;

import lombok.RequiredArgsConstructor;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 在常规工具目录授权和审批治理完成后，装饰 Provider。 */
@RequiredArgsConstructor
public final class EvaluationToolProvider implements ToolProvider {
    /** 被包装的原始实现，由本组件补充隔离、观测或恢复行为。 */
    private final ToolProvider delegate;

    /**
     * 查询列表中的评测工具提供方。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Mono<List<ToolDefinition>> list(ToolAdapterContext context) {
        return delegate.list(context);
    }

    /**
     * 调用评测工具提供方。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param id 目标对象的标识。
     * @param tool 当前评测工具提供方使用的工具，供其处理与状态记录使用。
     * @param input 本次处理的输入。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    public Mono<ToolResultBlock> invoke(
            ToolAdapterContext context, String id, String tool, Map<String, Object> input) {
        return Mono.defer(
                () -> {
                    EvaluationFixture fixture =
                            context.getRuntimeContext().get(EvaluationFixture.class);
                    if (fixture == null) return delegate.invoke(context, id, tool, input);
                    ToolResultBlock fault = fixture.fault(tool);
                    if (fault != null) return Mono.just(fault);
                    if ("CAPTURE_READONLY".equals(fixture.getMode())) {
                        var catalog =
                                context.getRuntimeContext()
                                        .get(getClass().getName(), ToolCatalogSnapshot.class);
                        boolean readOnly =
                                catalog != null
                                        && catalog.getTurnId().equals(context.getTurnId())
                                        && catalog.getDefinitions().stream()
                                                .anyMatch(
                                                        definition ->
                                                                definition.getName().equals(tool)
                                                                        && definition.isReadOnly());
                        if (!readOnly) return Mono.just(fixture.replay(tool, input, false));
                        ToolResultBlock replay = fixture.tryReplay(tool, input, true);
                        if (replay != null) return Mono.just(replay);
                        if (!fixture.allowLiveCall(tool))
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "LIVE_CALL_LIMIT: read-only capture budget exhausted"));
                        return delegate.invoke(context, id, tool, input)
                                .doOnNext(result -> fixture.record(tool, input, result));
                    }
                    if (!"REPLAY".equals(fixture.getMode()) && !fixture.allowLiveCall(tool)) {
                        ToolResultBlock blocked =
                                ToolResultBlock.error(
                                        "LIVE_CALL_LIMIT: this tool has exhausted its permitted real calls; use its"
                                                + " prior result");
                        if ("RECORD".equals(fixture.getMode()))
                            fixture.record(tool, input, blocked);
                        return Mono.just(blocked);
                    }
                    if ("LIVE".equals(fixture.getMode()))
                        return delegate.invoke(context, id, tool, input);
                    if ("REPLAY".equals(fixture.getMode())) {
                        var catalog =
                                context.getRuntimeContext()
                                        .get(getClass().getName(), ToolCatalogSnapshot.class);
                        boolean readOnly =
                                catalog != null
                                        && catalog.getTurnId().equals(context.getTurnId())
                                        && catalog.getDefinitions().stream()
                                                .anyMatch(
                                                        definition ->
                                                                definition.getName().equals(tool)
                                                                        && definition.isReadOnly());
                        return Mono.just(fixture.replay(tool, input, readOnly));
                    }
                    return delegate.invoke(context, id, tool, input)
                            .doOnNext(result -> fixture.record(tool, input, result));
                });
    }
}
