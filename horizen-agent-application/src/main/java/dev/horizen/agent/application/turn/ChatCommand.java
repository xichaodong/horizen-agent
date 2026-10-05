package dev.horizen.agent.application.turn;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.common.validation.Identifiers;

import lombok.Value;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Turn 用例的规范化输入，不依赖 HTTP DTO。 */
@Value
public class ChatCommand {
    /** 最大消息字符数的固定取值，用于相应策略和边界判断。 */
    public static final int MAX_MESSAGE_CHARS = 20_000;

    /** 最大产物集合的固定取值，用于相应策略和边界判断。 */
    public static final int MAX_ARTIFACTS = 20;

    /** 键校验模式的固定取值，用于相应策略和边界判断。 */
    public static final String KEY_PATTERN = Identifiers.CHAT_KEY_REGEX;

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前对话命令使用的字段，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String key(String value, String field) {
        String key = value == null ? "" : value.strip();
        if (!Identifiers.CHAT_KEY.matcher(key).matches())
            throw new ApplicationError(ApplicationError.Code.INVALID_ARGUMENT, field + " 非法");
        return key;
    }

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    String sessionId;

    /** 用户输入、响应说明或诊断消息，含义由所属协议对象限定。 */
    String message;

    /** 调用方提供的请求标识，用于区分重复提交和关联幂等处理。 */
    String requestId;

    /** 本次操作引用的产物标识集合，内容读取由产物服务处理。 */
    List<String> artifactIds;

    /**
     * 创建对话命令，初始化该组件所需的状态、配置或依赖。
     *
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @param requestId 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
     * @param artifactIds 本次操作引用的产物标识集合，内容读取由产物服务处理。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ChatCommand(
            String sessionId, String message, String requestId, List<String> artifactIds) {
        this.sessionId = key(sessionId, "sessionId");
        this.message = message == null ? "" : message.strip();
        if (this.message.isEmpty()
                || this.message.length() > MAX_MESSAGE_CHARS
                || this.message.indexOf(0) >= 0)
            throw new ApplicationError(
                    ApplicationError.Code.INVALID_ARGUMENT, "message 必须为 1 到 20000 个字符");
        this.requestId =
                requestId == null || requestId.isBlank()
                        ? UUID.randomUUID().toString()
                        : key(requestId, "requestId");
        var ids = artifactIds == null ? List.<String>of() : artifactIds;
        if (ids.size() > MAX_ARTIFACTS)
            throw new ApplicationError(
                    ApplicationError.Code.INVALID_ARGUMENT, "单轮最多引用 20 个 Artifact");
        var normalized = new LinkedHashSet<String>();
        for (String value : ids) {
            String id = value == null ? "" : value.strip();
            if (!id.matches("art_[A-Za-z0-9-]{8,120}"))
                throw new ApplicationError(ApplicationError.Code.INVALID_ARGUMENT, "artifactId 非法");
            normalized.add(id);
        }
        this.artifactIds = List.copyOf(normalized);
    }
}
