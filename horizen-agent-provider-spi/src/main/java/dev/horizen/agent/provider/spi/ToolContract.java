package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 版本化工具结构与执行策略，不依赖具体运行时。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolContract {
    /** 当前工具契约的名称，用于目录、调用或展示中的识别。 */
    private String name;

    /** 当前工具契约的用途说明，供目录或配置阅读者理解。 */
    private String description = "";

    /** 工具输入 JSON Schema，供参数校验与模型工具声明使用。 */
    private Map<String, Object> inputSchema = new LinkedHashMap<>();

    /** 读取只读的状态标记，用于选择当前组件的处理路径。 */
    private boolean readOnly;

    /** 工具目录声明的风险级别，供宿主执行治理使用。 */
    private RiskLevel riskLevel = RiskLevel.MEDIUM;

    /** 超时，单位为秒。 */
    private int timeoutSeconds = 30;

    /** 幂等的状态标记，用于选择当前组件的处理路径。 */
    private boolean idempotent;

    /** 并发安全的状态标记，用于选择当前组件的处理路径。 */
    private boolean concurrencySafe = true;

    /** 支持取消的状态标记，用于选择当前组件的处理路径。 */
    private boolean supportsCancellation;

    /** 工具执行前的人工确认策略，不替代业务访问授权。 */
    private ApprovalPolicy approvalPolicy = ApprovalPolicy.NONE;

    /** 工具所属分组的稳定名称或分组契约。 */
    private ToolGroupContract group = new ToolGroupContract();

    /**
     * 校验当前工具契约的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的工具契约结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ToolContract validate() {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("tool name must be lower snake_case");
        }
        if (description == null) description = "";
        if (inputSchema == null || !"object".equals(inputSchema.get("type"))) {
            throw new IllegalArgumentException("tool inputSchema must be an object schema");
        }
        inputSchema = Map.copyOf(inputSchema);
        if (riskLevel == null) throw new IllegalArgumentException("riskLevel is required");
        if (timeoutSeconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        if (approvalPolicy == null)
            throw new IllegalArgumentException("approvalPolicy is required");
        if (group == null) group = new ToolGroupContract();
        group.validate();
        return this;
    }
}
