package dev.horizen.agent.web.api;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.turn.ChatCommand;
import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.web.api.chat.ChatApi;

import org.springframework.http.HttpStatus;

/** 在 HTTP 边界完成校验，领域和应用服务接收规范化值。 */
public final class AgentRequestValidator {

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private AgentRequestValidator() {}

    /**
     * 校验对话。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的对话命令结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static ChatCommand validateChat(ChatApi.ChatRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请求不能为空");
        }
        try {
            return new ChatCommand(
                    request.getSessionId(),
                    request.getMessage(),
                    request.getRequestId(),
                    request.getArtifactIds());
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        }
    }

    /**
     * 生成当前操作所需的sessionId文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String sessionId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!Identifiers.CHAT_KEY.matcher(normalized).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessionId 非法");
        }
        return normalized;
    }

    /**
     * 生成当前操作所需的turnId文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String turnId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!Identifiers.CHAT_KEY.matcher(normalized).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "turnId 非法");
        }
        return normalized;
    }

    /**
     * 生成当前操作所需的artifactId文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static String artifactId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("art_[A-Za-z0-9-]{8,120}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "artifactId 非法");
        }
        return normalized;
    }

    /**
     * 生成当前操作所需的artifactTitle文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    static String artifactTitle(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) normalized = "upload.bin";
        normalized = normalized.replace('\\', '_').replace('/', '_').replace('\0', '_');
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentRequestValidator处理步骤使用。
     *
     * @param cursor 当前分页或回放位置，用于继续读取而不是资源身份校验。
     * @return 本次操作返回的整数结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws NumberFormatException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static int sessionCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int value = Integer.parseInt(cursor);
            if (value < 0) throw new NumberFormatException("negative");
            return value;
        } catch (NumberFormatException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cursor 非法");
        }
    }

    /**
     * 生成当前操作所需的taskId文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static String taskId(String value) {
        if (value == null || !value.matches("task_[A-Za-z0-9-]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "taskId 非法");
        }
        return value;
    }
}
