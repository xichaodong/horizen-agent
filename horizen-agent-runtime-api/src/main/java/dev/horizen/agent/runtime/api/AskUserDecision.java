package dev.horizen.agent.runtime.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 运行时恢复执行需要的澄清决定，与原请求标识关联。
 */
@RequiredArgsConstructor
@Getter
public final class AskUserDecision {
    /**
     * 待回答澄清请求的标识，恢复时与原问题记录关联。
     */
    private final String askUserId;

    /**
     * 一次工具调用的标识，用于配对参数、结果和审批事件。
     */
    private final String toolCallId;

    /**
     * 回答集合的 JSON 表示，供持久化或协议转换使用。
     */
    private final String answersJson;
}
