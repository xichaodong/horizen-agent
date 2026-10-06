package dev.horizen.agent.web.api.interaction;

import dev.horizen.agent.runtime.api.ApprovalPresentation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 此 API 功能域的 HTTP/SSE 传输模型。
 */
public final class InteractionApi {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private InteractionApi() {
    }

    /**
     * 提问用户回答的接口请求，承载调用方提交的定位信息与输入。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AskUserAnswerRequest {
        /**
         * 待回答澄清请求的标识，恢复时与原问题记录关联。
         */
        private String askUserId;

        /**
         * 用户对澄清问题的回答集合，按问题标识关联选项和补充文本。
         */
        private List<Map<String, Object>> answers;

        /**
         * 是否跳过本次澄清请求；跳过与提交具体答案分开表示。
         */
        private boolean skip;
    }

    /**
     * 审批查询的接口请求，承载调用方提交的定位信息与输入。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalQueryRequest {
        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        private String sessionId;

        /**
         * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
         */
        private String turnId;
    }

    /**
     * 审批决定的接口请求，承载调用方提交的定位信息与输入。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalDecisionRequest {
        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        private String sessionId;

        /**
         * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
         */
        private String turnId;

        /**
         * 同一批待确认操作的决定集合，用于恢复原执行。
         */
        private List<ApprovalChoice> decisions;
    }

    /**
     * 交互API内部的审批Choice，封装该步骤需要的状态或输入输出。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalChoice {
        /**
         * 待确认操作的记录标识，提交决定时用它定位原审批。
         */
        private String approvalId;

        /**
         * 本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
         */
        private boolean approved;
    }

    /**
     * 审批集合的接口响应，承载已完成查询或操作的结果。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalsResponse {
        /**
         * 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         */
        private String sessionId;

        /**
         * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
         */
        private String turnId;

        /**
         * 审批存储或待处理审批集合，用于原执行的暂停与恢复。
         */
        private List<ApprovalResponse> approvals;
    }

    /**
     * 审批的接口响应，承载已完成查询或操作的结果。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalResponse {
        /**
         * 待确认操作的记录标识，提交决定时用它定位原审批。
         */
        private String approvalId;

        /**
         * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
         */
        private String turnId;

        /**
         * 一次工具调用的标识，用于配对参数、结果和审批事件。
         */
        private String toolCallId;

        /**
         * 可调用工具的注册名称，须与目录中声明的名称一致。
         */
        private String toolName;

        /**
         * 工具调用参数，按工具目录中的输入 Schema 解释。
         */
        private Map<String, Object> arguments;

        /**
         * 当前记录或执行的状态，具体取值由所属领域或协议约定。
         */
        private String status;

        /**
         * 当前记录或授权的失效时间，用于过期检查。
         */
        private Instant expiresAt;

        /**
         * 面向宿主界面的结构化呈现信息，与实际执行结果分开处理。
         */
        private ApprovalPresentation presentation;
    }
}
