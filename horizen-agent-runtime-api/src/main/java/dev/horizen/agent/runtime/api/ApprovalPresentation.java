package dev.horizen.agent.runtime.api;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 仅用于展示的审批快照，不能作为执行参数或授权依据。
 */
@Data
@NoArgsConstructor
public class ApprovalPresentation {
    /**
     * 当前审批呈现的可读标题，供宿主界面展示。
     */
    private String title;

    /**
     * 当前限制、治理或审批要求的可读原因。
     */
    private String reason;

    /**
     * 审批操作执行后可能产生的影响说明。
     */
    private String impact;

    /**
     * 字段集合的索引映射，供按键查找或归并当前组件的数据。
     */
    private Map<String, String> fields;

    /**
     * 创建审批呈现，初始化该组件所需的状态、配置或依赖。
     *
     * @param title  当前审批呈现的可读标题，供宿主界面展示。
     * @param reason 当前审批呈现使用的原因，供其处理与状态记录使用。
     * @param impact 当前审批呈现使用的影响，供其处理与状态记录使用。
     * @param fields 字段集合的索引映射，供按键查找或归并当前组件的数据。
     */
    public ApprovalPresentation(
            String title, String reason, String impact, Map<String, String> fields) {
        this.title = title;
        this.reason = reason;
        this.impact = impact;
        this.fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param toolName 可调用工具的注册名称，须与目录中声明的名称一致。
     * @return 本次操作返回的审批呈现结果。
     */
    public static ApprovalPresentation fallback(String toolName) {
        return new ApprovalPresentation("确认执行 " + toolName, "当前执行策略要求先确认这次操作。", "", Map.of());
    }
}
