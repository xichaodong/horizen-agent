package dev.horizen.agent.runtime.api;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.util.Map;

/**
 * 宿主提交给 Runtime 的一次工具审批决定。
 */
@Getter
@EqualsAndHashCode
@ToString
public class ToolApprovalDecision {
    /**
     * 一次工具调用的标识，用于配对参数、结果和审批事件。
     */
    private final String toolCallId;

    /**
     * 可调用工具的注册名称，须与目录中声明的名称一致。
     */
    private final String toolName;

    /**
     * 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     */
    private final String content;

    /**
     * 当前操作的输入数据，格式由所属命令、协议或工具定义。
     */
    private final Map<String, Object> input;

    /**
     * 本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
     */
    private final boolean approved;

    /**
     * 创建工具审批决定，初始化该组件所需的状态、配置或依赖。
     *
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param toolName   可调用工具的注册名称，须与目录中声明的名称一致。
     * @param content    当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param input      本次处理的输入。
     * @param approved   本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({"toolCallId", "toolName", "content", "input", "approved"})
    public ToolApprovalDecision(
            String toolCallId,
            String toolName,
            String content,
            Map<String, Object> input,
            boolean approved) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId 不能为空");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        content = content == null ? "" : content;
        input = input == null ? Map.of() : Map.copyOf(input);

        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.content = content;
        this.input = input;
        this.approved = approved;
    }
}
