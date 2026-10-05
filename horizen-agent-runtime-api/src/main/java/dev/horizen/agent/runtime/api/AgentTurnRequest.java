package dev.horizen.agent.runtime.api;

import lombok.Setter;
import lombok.Value;
import lombok.experimental.Accessors;

import java.beans.ConstructorProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 一次用户输入触发的 Agent Turn；Runtime 只使用宿主提供的不透明身份键。 */
@Value
public class AgentTurnRequest {
    /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
    private String turnId;

    /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
    private String ownerKey;

    /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
    private String sessionId;

    /** 用户输入、响应说明或诊断消息，含义由所属协议对象限定。 */
    private String message;

    /** 当前恢复请求提交的审批决定集合。 */
    private List<ToolApprovalDecision> approvalDecisions;

    /** 当前恢复请求提交的澄清回答集合。 */
    private List<AskUserDecision> askUserDecisions;

    /** 本次消息附带的输入资源，内容解析由运行时适配器完成。 */
    private List<AgentInputAttachment> attachments;

    /** 宿主绑定的类型化上下文，不作为模型工具参数暴露。 */
    private List<AgentContextBinding<?>> contextBindings;

    /** 校验安全键的模式，限定允许接受的输入形式。 */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    /**
     * 创建Agent执行请求，初始化该组件所需的状态、配置或依赖。
     *
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @param approvalDecisions 当前恢复请求提交的审批决定集合。
     * @param askUserDecisions 当前恢复请求提交的澄清回答集合。
     * @param contextBindings 宿主绑定的类型化上下文，不作为模型工具参数暴露。
     */
    public AgentTurnRequest(
            String turnId,
            String ownerKey,
            String sessionId,
            String message,
            List<ToolApprovalDecision> approvalDecisions,
            List<AskUserDecision> askUserDecisions,
            List<AgentContextBinding<?>> contextBindings) {
        this(
                turnId,
                ownerKey,
                sessionId,
                message,
                approvalDecisions,
                askUserDecisions,
                List.of(),
                contextBindings);
    }

    /**
     * 创建Agent执行请求，初始化该组件所需的状态、配置或依赖。
     *
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @param approvalDecisions 当前恢复请求提交的审批决定集合。
     * @param askUserDecisions 当前恢复请求提交的澄清回答集合。
     * @param attachments 本次消息附带的输入资源，内容解析由运行时适配器完成。
     * @param contextBindings 宿主绑定的类型化上下文，不作为模型工具参数暴露。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
        "turnId",
        "ownerKey",
        "sessionId",
        "message",
        "approvalDecisions",
        "askUserDecisions",
        "attachments",
        "contextBindings"
    })
    public AgentTurnRequest(
            String turnId,
            String ownerKey,
            String sessionId,
            String message,
            List<ToolApprovalDecision> approvalDecisions,
            List<AskUserDecision> askUserDecisions,
            List<AgentInputAttachment> attachments,
            List<AgentContextBinding<?>> contextBindings) {
        turnId = requireKey(turnId, "turnId");
        ownerKey = requireKey(ownerKey, "ownerKey");
        sessionId = requireKey(sessionId, "sessionId");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        approvalDecisions = approvalDecisions == null ? List.of() : List.copyOf(approvalDecisions);
        askUserDecisions = askUserDecisions == null ? List.of() : List.copyOf(askUserDecisions);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        contextBindings = contextBindings == null ? List.of() : List.copyOf(contextBindings);

        this.turnId = turnId;
        this.ownerKey = ownerKey;
        this.sessionId = sessionId;
        this.message = message;
        this.approvalDecisions = approvalDecisions;
        this.askUserDecisions = askUserDecisions;
        this.attachments = attachments;
        this.contextBindings = contextBindings;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的构造器结果。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 取得并校验键。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name 需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireKey(String value, String name) {
        if (value == null || !SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be an opaque safe key");
        }
        return value;
    }

    /**
     * 生成只包含必要摘要的诊断文本，避免直接输出绑定内容或消息正文。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String toString() {
        return "AgentTurnRequest[turnId="
                + turnId
                + ", ownerKey=[bound], sessionId="
                + sessionId
                + ", messageLength="
                + message.length()
                + ", approvalDecisionCount="
                + approvalDecisions.size()
                + ", attachmentCount="
                + attachments.size()
                + ", contextTypes="
                + contextBindings.stream().map(binding -> binding.getType().getName()).toList()
                + "]";
    }

    /** 执行请求构造器，按字段收集可信上下文、输入附件与恢复决定。 */
    @Setter
    @Accessors(fluent = true, chain = true)
    public static final class Builder {
        /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
        private String turnId;

        /** 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。 */
        private String ownerKey;

        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 用户输入、响应说明或诊断消息，含义由所属协议对象限定。 */
        private String message;

        /** 当前恢复请求提交的审批决定集合。 */
        private final List<ToolApprovalDecision> approvalDecisions = new ArrayList<>();

        /** 当前恢复请求提交的澄清回答集合。 */
        private final List<AskUserDecision> askUserDecisions = new ArrayList<>();

        /** 本次消息附带的输入资源，内容解析由运行时适配器完成。 */
        private final List<AgentInputAttachment> attachments = new ArrayList<>();

        /** 会话与发布或宿主上下文之间的绑定信息，供执行恢复使用。 */
        private final List<AgentContextBinding<?>> bindings = new ArrayList<>();

        /**
         * 读取审批决定集合的当前值。
         *
         * @param values 本次批量处理的值集合。
         * @return {@link #approvalDecisions} 中保存的值。
         */
        public Builder approvalDecisions(List<ToolApprovalDecision> values) {
            approvalDecisions.clear();
            if (values != null) {
                approvalDecisions.addAll(values);
            }
            return this;
        }

        /**
         * 读取提问用户决定集合的当前值。
         *
         * @param values 本次批量处理的值集合。
         * @return {@link #askUserDecisions} 中保存的值。
         */
        public Builder askUserDecisions(List<AskUserDecision> values) {
            askUserDecisions.clear();
            if (values != null) askUserDecisions.addAll(values);
            return this;
        }

        /**
         * 读取attachments的当前值。
         *
         * @param values 本次批量处理的值集合。
         * @return {@link #attachments} 中保存的值。
         */
        public Builder attachments(List<AgentInputAttachment> values) {
            attachments.clear();
            if (values != null) attachments.addAll(values);
            return this;
        }

        /**
         * 计算或取得本方法声明的结果，供当前Builder处理步骤使用。
         *
         * @param type 当前操作使用的目标类型或类别。
         * @param value 待校验、转换或保存的原始值。
         * @return 本次操作返回的构造器结果。
         */
        public <T> Builder context(Class<T> type, T value) {
            bindings.add(new AgentContextBinding<>(type, value));
            return this;
        }

        /**
         * 构造构造器。
         *
         * @return 本次操作返回的Agent执行请求结果。
         */
        public AgentTurnRequest build() {
            return new AgentTurnRequest(
                    turnId,
                    ownerKey,
                    sessionId,
                    message,
                    approvalDecisions,
                    askUserDecisions,
                    attachments,
                    bindings);
        }
    }
}
