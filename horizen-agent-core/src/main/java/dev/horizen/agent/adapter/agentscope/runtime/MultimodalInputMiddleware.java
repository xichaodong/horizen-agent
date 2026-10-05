package dev.horizen.agent.adapter.agentscope.runtime;

import dev.horizen.agent.runtime.api.AgentInputAttachment;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 仅在模型推理时加载当前 Turn 的图像 Artifact，不写入持久化状态。 */
public final class MultimodalInputMiddleware implements MiddlewareBase {
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
        MultimodalTurnInput multimodal =
                context == null ? null : context.get(MultimodalTurnInput.class);
        if (multimodal == null || multimodal.getAttachments().isEmpty()) return next.apply(input);
        List<Msg> messages = new ArrayList<>(input.messages());
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (message.getRole() != MsgRole.USER) continue;
            if (message.hasContentBlocks(ImageBlock.class)) break;
            List<ContentBlock> content = new ArrayList<>(message.getContent());
            content.add(
                    TextBlock.builder()
                            .text("以下图片已在当前模型请求中提供，可直接分析，无需再次调用 vision_analyze。")
                            .build());
            for (AgentInputAttachment attachment : multimodal.getAttachments()) {
                content.add(
                        TextBlock.builder()
                                .text(
                                        "图片 Artifact "
                                                + attachment.getArtifactId()
                                                + "（"
                                                + attachment.getTitle()
                                                + "）")
                                .build());
                content.add(
                        ImageBlock.builder()
                                .source(
                                        new URLSource(
                                                attachment.getUrl(), attachment.getMediaType()))
                                .build());
            }
            messages.set(index, message.withContent(content));
            break;
        }
        return next.apply(
                new ReasoningInput(List.copyOf(messages), input.tools(), input.options()));
    }
}
