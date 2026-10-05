package dev.horizen.agent.runtime.api;

import lombok.Data;

import java.beans.ConstructorProperties;
import java.util.Map;

/** 对外暴露的待审批工具调用，不泄漏 AgentScope 事件类型。 */
@Data
public class ToolApprovalRequest {
    /** 回复的标识，用于关联相应记录或执行。 */
    private String replyId;

    /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
    private String toolCallId;

    /** 可调用工具的注册名称，须与目录中声明的名称一致。 */
    private String toolName;

    /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
    private String content;

    /** 当前操作的输入数据，格式由所属命令、协议或工具定义。 */
    private Map<String, Object> input;

    /** 面向宿主界面的结构化呈现信息，与实际执行结果分开处理。 */
    private ApprovalPresentation presentation;

    /**
     * 创建工具审批请求，初始化该组件所需的状态、配置或依赖。
     *
     * @param replyId 回复的标识，用于关联相应记录或执行。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param toolName 可调用工具的注册名称，须与目录中声明的名称一致。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param input 本次处理的输入。
     */
    @ConstructorProperties({"replyId", "toolCallId", "toolName", "content", "input"})
    public ToolApprovalRequest(
            String replyId,
            String toolCallId,
            String toolName,
            String content,
            Map<String, Object> input) {
        input = input == null ? Map.of() : Map.copyOf(input);

        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.content = content;
        this.input = input;
    }
}
