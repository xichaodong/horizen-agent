package dev.horizen.agent.tool.governance;

import dev.horizen.agent.tool.adapter.ToolDefinition;

import lombok.Getter;

/** 由宿主管理的执行元数据；JSON Schema 仍由 AgentScope 工具本身提供。 */
@Getter
public final class ToolDescriptor {
    /** 当前工具描述的名称，用于目录、调用或展示中的识别。 */
    private final String name;

    /** 工具所属分组的稳定名称或分组契约。 */
    private final String group;

    /** 读取只读的状态标记，用于选择当前组件的处理路径。 */
    private final boolean readOnly;

    /** 工具目录声明的风险级别，供宿主执行治理使用。 */
    private final String riskLevel;

    /** 超时，单位为秒。 */
    private final int timeoutSeconds;

    /** 幂等的状态标记，用于选择当前组件的处理路径。 */
    private final boolean idempotent;

    /** 并发安全的状态标记，用于选择当前组件的处理路径。 */
    private final boolean concurrencySafe;

    /** 支持取消的状态标记，用于选择当前组件的处理路径。 */
    private final boolean supportsCancellation;

    /** 工具执行前的人工确认策略，不替代业务访问授权。 */
    private final String approvalPolicy;

    /** 提供方工具的状态标记，用于选择当前组件的处理路径。 */
    private final boolean providerTool;

    /**
     * 创建工具描述，初始化该组件所需的状态、配置或依赖。
     *
     * @param name 需要定位或处理的名称。
     * @param group 当前工具描述使用的分组，供其处理与状态记录使用。
     * @param readOnly 读取只读的状态标记，用于选择当前组件的处理路径。
     * @param riskLevel 当前工具描述使用的风险级别，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     * @param idempotent 幂等的状态标记，用于选择当前组件的处理路径。
     * @param concurrencySafe 并发安全的状态标记，用于选择当前组件的处理路径。
     * @param supportsCancellation 支持取消的状态标记，用于选择当前组件的处理路径。
     * @param approvalPolicy 当前工具描述使用的审批策略，供其处理与状态记录使用。
     */
    public ToolDescriptor(
            String name,
            String group,
            boolean readOnly,
            String riskLevel,
            int timeoutSeconds,
            boolean idempotent,
            boolean concurrencySafe,
            boolean supportsCancellation,
            String approvalPolicy) {
        this(
                name,
                group,
                readOnly,
                riskLevel,
                timeoutSeconds,
                idempotent,
                concurrencySafe,
                supportsCancellation,
                approvalPolicy,
                false);
    }

    /**
     * 创建工具描述，初始化该组件所需的状态、配置或依赖。
     *
     * @param name 需要定位或处理的名称。
     * @param group 当前工具描述使用的分组，供其处理与状态记录使用。
     * @param readOnly 读取只读的状态标记，用于选择当前组件的处理路径。
     * @param riskLevel 当前工具描述使用的风险级别，供其处理与状态记录使用。
     * @param timeoutSeconds 超时，单位为秒。
     * @param idempotent 幂等的状态标记，用于选择当前组件的处理路径。
     * @param concurrencySafe 并发安全的状态标记，用于选择当前组件的处理路径。
     * @param supportsCancellation 支持取消的状态标记，用于选择当前组件的处理路径。
     * @param approvalPolicy 当前工具描述使用的审批策略，供其处理与状态记录使用。
     * @param providerTool 提供方工具的状态标记，用于选择当前组件的处理路径。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ToolDescriptor(
            String name,
            String group,
            boolean readOnly,
            String riskLevel,
            int timeoutSeconds,
            boolean idempotent,
            boolean concurrencySafe,
            boolean supportsCancellation,
            String approvalPolicy,
            boolean providerTool) {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,127}"))
            throw new IllegalArgumentException("invalid tool name");
        if (group == null || group.isBlank()) throw new IllegalArgumentException("group required");
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeout must be positive");
        this.name = name;
        this.group = group;
        this.readOnly = readOnly;
        this.riskLevel = riskLevel == null ? "" : riskLevel;
        this.timeoutSeconds = timeoutSeconds;
        this.idempotent = idempotent;
        this.concurrencySafe = concurrencySafe;
        this.supportsCancellation = supportsCancellation;
        this.approvalPolicy = approvalPolicy == null ? "none" : approvalPolicy;
        this.providerTool = providerTool;
    }

    /**
     * 从输入构造定义。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的工具描述结果。
     */
    public static ToolDescriptor fromDefinition(ToolDefinition value) {
        return new ToolDescriptor(
                value.getName(),
                value.getGroup().getId(),
                value.isReadOnly(),
                value.getRiskLevel(),
                value.getTimeoutSeconds(),
                value.isIdempotent(),
                value.isConcurrencySafe(),
                value.isSupportsCancellation(),
                value.getApprovalPolicy(),
                true);
    }
}
