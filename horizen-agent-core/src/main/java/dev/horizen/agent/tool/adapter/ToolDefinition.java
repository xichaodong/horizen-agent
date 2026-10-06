package dev.horizen.agent.tool.adapter;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 内置工具与动态加载的 Provider 工具共享的标准工具契约。
 */
@Getter
@EqualsAndHashCode
public final class ToolDefinition {
    /**
     * 校验工具名称的模式，限定允许接受的输入形式。
     */
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,127}");

    /**
     * 当前工具定义的名称，用于目录、调用或展示中的识别。
     */
    private final String name;

    /**
     * 当前工具定义的用途说明，供目录或配置阅读者理解。
     */
    private final String description;

    /**
     * 工具输入 JSON Schema，供参数校验与模型工具声明使用。
     */
    private final Map<String, Object> inputSchema;

    /**
     * 读取只读的状态标记，用于选择当前组件的处理路径。
     */
    private final boolean readOnly;

    /**
     * 工具目录声明的风险级别，供宿主执行治理使用。
     */
    private final String riskLevel;

    /**
     * 超时，单位为秒。
     */
    private final int timeoutSeconds;

    /**
     * 幂等的状态标记，用于选择当前组件的处理路径。
     */
    private final boolean idempotent;

    /**
     * 并发安全的状态标记，用于选择当前组件的处理路径。
     */
    private final boolean concurrencySafe;

    /**
     * 支持取消的状态标记，用于选择当前组件的处理路径。
     */
    private final boolean supportsCancellation;

    /**
     * 工具执行前的人工确认策略，不替代业务访问授权。
     */
    private final String approvalPolicy;

    /**
     * 工具所属分组的稳定名称或分组契约。
     */
    private final ToolGroupDefinition group;

    /**
     * 创建工具定义，初始化该组件所需的状态、配置或依赖。
     *
     * @param name             需要定位或处理的名称。
     * @param description      当前工具定义的用途说明，供目录或配置阅读者理解。
     * @param inputSchema      输入Schema的索引映射，供按键查找或归并当前组件的数据。
     * @param riskLevel        当前工具定义使用的风险级别，供其处理与状态记录使用。
     * @param requiresApproval requires审批的状态标记，用于选择当前组件的处理路径。
     */
    public ToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema,
            String riskLevel,
            boolean requiresApproval) {
        this(
                name,
                description,
                inputSchema,
                false,
                riskLevel,
                30,
                false,
                true,
                false,
                requiresApproval ? "required" : "none",
                ToolGroupDefinition.externalDefault());
    }

    /**
     * 创建工具定义，初始化该组件所需的状态、配置或依赖。
     *
     * @param name                 需要定位或处理的名称。
     * @param description          当前工具定义的用途说明，供目录或配置阅读者理解。
     * @param inputSchema          输入Schema的索引映射，供按键查找或归并当前组件的数据。
     * @param readOnly             读取只读的状态标记，用于选择当前组件的处理路径。
     * @param riskLevel            当前工具定义使用的风险级别，供其处理与状态记录使用。
     * @param timeoutSeconds       超时，单位为秒。
     * @param idempotent           幂等的状态标记，用于选择当前组件的处理路径。
     * @param concurrencySafe      并发安全的状态标记，用于选择当前组件的处理路径。
     * @param supportsCancellation 支持取消的状态标记，用于选择当前组件的处理路径。
     * @param approvalPolicy       当前工具定义使用的审批策略，供其处理与状态记录使用。
     * @param group                当前工具定义持有的分组对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            String riskLevel,
            int timeoutSeconds,
            boolean idempotent,
            boolean concurrencySafe,
            boolean supportsCancellation,
            String approvalPolicy,
            ToolGroupDefinition group) {
        if (name == null || !TOOL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("tool name must be lower snake_case");
        }
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
        if (!"object".equals(inputSchema.get("type"))) {
            throw new IllegalArgumentException("tool inputSchema must be an object schema");
        }
        if (timeoutSeconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        approvalPolicy =
                approvalPolicy == null || approvalPolicy.isBlank()
                        ? "none"
                        : approvalPolicy.trim().toLowerCase(Locale.ROOT);
        if (!approvalPolicy.equals("none") && !approvalPolicy.equals("required")) {
            throw new IllegalArgumentException("approvalPolicy must be none or required");
        }
        this.name = name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema;
        this.readOnly = readOnly;
        this.riskLevel = riskLevel == null ? "" : riskLevel;
        this.timeoutSeconds = timeoutSeconds;
        this.idempotent = idempotent;
        this.concurrencySafe = concurrencySafe;
        this.supportsCancellation = supportsCancellation;
        this.approvalPolicy = approvalPolicy;
        this.group = group == null ? ToolGroupDefinition.externalDefault() : group;
    }

    /**
     * 检查requiresApproval对应的条件，供调用方选择后续处理分支。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean requiresApproval() {
        return "required".equals(approvalPolicy);
    }
}
