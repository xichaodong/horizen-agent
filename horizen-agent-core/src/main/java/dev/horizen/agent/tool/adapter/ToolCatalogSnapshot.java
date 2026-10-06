package dev.horizen.agent.tool.adapter;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 解析适配器已授权工具后得到的不可变结果，作用域限于当前 Turn。
 */
@Data
@NoArgsConstructor
public class ToolCatalogSnapshot {
    /**
     * 适配器的标识，用于关联相应记录或执行。
     */
    private String adapterId;

    /**
     * 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     */
    private String turnId;

    /**
     * 当前记录的创建时间。
     */
    private Instant createdAt;

    /**
     * definitions的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    private List<ToolDefinition> definitions;

    /**
     * 创建工具目录快照，初始化该组件所需的状态、配置或依赖。
     *
     * @param adapterId   适配器的标识，用于关联相应记录或执行。
     * @param turnId      单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param createdAt   当前记录的创建时间。
     * @param definitions definitions的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ToolCatalogSnapshot(
            String adapterId, String turnId, Instant createdAt, List<ToolDefinition> definitions) {
        if (adapterId == null || adapterId.isBlank() || turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("adapterId and turnId must not be blank");
        }
        this.adapterId = adapterId;
        this.turnId = turnId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
    }
}
