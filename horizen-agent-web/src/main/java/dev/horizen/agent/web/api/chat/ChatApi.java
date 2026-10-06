package dev.horizen.agent.web.api.chat;

import dev.horizen.agent.application.turn.ChatCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 此 API 功能域的 HTTP/SSE 传输模型。
 */
public final class ChatApi {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ChatApi() {
    }

    /**
     * 对话的接口请求，承载调用方提交的定位信息与输入。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        @NotBlank
        @Pattern(regexp = ChatCommand.KEY_PATTERN)
        private String sessionId;

        /**
         * 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
         */
        @NotBlank
        @Size(max = ChatCommand.MAX_MESSAGE_CHARS)
        private String message;

        /**
         * 调用方提供的请求标识，用于区分重复提交和关联幂等处理。
         */
        private String requestId;

        /**
         * 本次操作引用的产物标识集合，内容读取由产物服务处理。
         */
        @Size(max = ChatCommand.MAX_ARTIFACTS)
        private List<String> artifactIds;

        /**
         * 保存会话标识，非空输入先移除首尾空白，空值保持为空。
         *
         * @param value 调用方提交的会话标识。
         */
        public void setSessionId(String value) {
            sessionId = value == null ? null : value.strip();
        }

        /**
         * 保存用户输入，非空正文先移除首尾空白，空值保持为空。
         *
         * @param value 调用方提交的原始消息文本。
         */
        public void setMessage(String value) {
            message = value == null ? null : value.strip();
        }
    }

    /**
     * 对话的接口响应，承载已完成查询或操作的结果。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResponse {
        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        private String sessionId;

        /**
         * 本次执行返回的文本回复，供宿主消息接口输出。
         */
        private String reply;

        /**
         * 当前执行请求的总耗时，单位为毫秒。
         */
        private long latencyMs;
    }

    /**
     * 对话API内部的对话事件流事件，封装该步骤需要的状态或输入输出。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatStreamEvent {
        /**
         * 本对象的协议类别，用于选择对应的解析或呈现规则。
         */
        private String type;

        /**
         * 当前对话事件流事件的定位标识。
         */
        private String id;

        /**
         * 当前对话事件流事件的可读标题，供宿主界面展示。
         */
        private String title;

        /**
         * 面向消息或事件消费者的文本内容。
         */
        private String text;

        /**
         * 当前记录或执行的状态，具体取值由所属领域或协议约定。
         */
        private String status;

        /**
         * 可调用工具的注册名称，须与目录中声明的名称一致。
         */
        private String toolName;

        /**
         * 当前事件或查询结果的补充细节，供状态解释与展示使用。
         */
        private String details;

        /**
         * 当前执行步骤的耗时，单位为毫秒。
         */
        private Long durationMs;

        /**
         * 当前执行请求的总耗时，单位为毫秒。
         */
        private Long latencyMs;

        /**
         * 当前事件、内容或执行的来源，供追踪生成关系与执行层级使用。
         */
        private String source;

        /**
         * 任务的标识，用于关联相应记录或执行。
         */
        private String taskId;

        /**
         * 父级会话标识，用于关联委派任务与其发起会话。
         */
        private String parentSessionId;

        /**
         * 当前执行 Agent 的标识，用于区分主 Agent 与委派执行者。
         */
        private String agentId;

        /**
         * 当前节点在执行树中的嵌套深度，供宿主分层展示。
         */
        private Integer depth;

        /**
         * 活跃 Turn 的 Redis List 游标，与 MySQL 时间线序号无关。
         */
        private Long streamSequence;

        /**
         * 兼容向浏览器暴露流游标前调用方式的构造方法。
         */
        public ChatStreamEvent(
                String type,
                String id,
                String title,
                String text,
                String status,
                String toolName,
                String details,
                Long durationMs,
                Long latencyMs,
                String source,
                String taskId,
                String parentSessionId,
                String agentId,
                Integer depth) {
            this(
                    type,
                    id,
                    title,
                    text,
                    status,
                    toolName,
                    details,
                    durationMs,
                    latencyMs,
                    source,
                    taskId,
                    parentSessionId,
                    agentId,
                    depth,
                    null);
        }

        /**
         * 创建对话事件流事件，初始化该组件所需的状态、配置或依赖。
         *
         * @param type       当前操作使用的目标类型或类别。
         * @param id         目标对象的标识。
         * @param title      当前对话事件流事件的可读标题，供宿主界面展示。
         * @param text       面向消息或事件消费者的文本内容。
         * @param status     当前记录或执行的状态，具体取值由所属领域或协议约定。
         * @param toolName   可调用工具的注册名称，须与目录中声明的名称一致。
         * @param details    当前事件或查询结果的补充细节，供状态解释与展示使用。
         * @param durationMs 当前执行步骤的耗时，单位为毫秒。
         * @param latencyMs  当前执行请求的总耗时，单位为毫秒。
         */
        public ChatStreamEvent(
                String type,
                String id,
                String title,
                String text,
                String status,
                String toolName,
                String details,
                Long durationMs,
                Long latencyMs) {
            this(
                    type,
                    id,
                    title,
                    text,
                    status,
                    toolName,
                    details,
                    durationMs,
                    latencyMs,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
