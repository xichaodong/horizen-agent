package dev.horizen.agent.context;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;

/**
 * AgentScope 摘要失败或为空时的占位内容，绝不能替换对话历史。
 */
final class CompactionSummary {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private CompactionSummary() {
    }

    /**
     * 检查failed对应的条件，供调用方选择后续处理分支。
     *
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    static boolean failed(Msg message) {
        if (message == null || !ConversationCompactor.SUMMARY_MSG_NAME.equals(message.getName())) {
            return false;
        }
        String text = message.getTextContent();
        return text == null
                || text.isBlank()
                || text.contains("(Summarization failed:")
                || text.contains("(Summary unavailable)");
    }
}
