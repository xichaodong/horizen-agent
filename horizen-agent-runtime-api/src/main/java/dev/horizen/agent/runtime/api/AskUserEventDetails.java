package dev.horizen.agent.runtime.api;

import dev.horizen.agent.domain.askuser.AskUserRequest;

import lombok.Value;

/** 宿主事件与 Redis 回放共用的澄清请求细节，维护一致的事件字段。 */
@Value
public class AskUserEventDetails {
    /** 待回答澄清请求的标识，恢复时与原问题记录关联。 */
    String askUserId;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    String sessionId;

    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    String turnId;

    /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
    String toolCallId;

    /** 问题集合的 JSON 表示，供持久化或协议转换使用。 */
    String questionsJson;

    /** 当前记录或授权的失效时间，用于过期检查。 */
    String expiresAt;

    /**
     * 规范化提问用户事件细节。
     *
     * @param details 当前事件或查询结果的补充细节，供状态解释与展示使用。
     * @return 本次操作返回的对象结果。
     */
    public static Object normalize(Object details) {
        if (!(details instanceof AskUserRequest value)) return details;
        return new AskUserEventDetails(
                value.getAskUserId(),
                value.getSessionId(),
                value.getTurnId(),
                value.getToolCallId(),
                value.getQuestionsJson(),
                value.getExpiresAt().toString());
    }
}
