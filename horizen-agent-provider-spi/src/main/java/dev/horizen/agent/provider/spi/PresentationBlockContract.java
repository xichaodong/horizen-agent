package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 外部工具可返回的单个结构化呈现块。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PresentationBlockContract {
    /** 本对象的协议类别，用于选择对应的解析或呈现规则。 */
    private String type;

    /** Schema的版本，供兼容或并发检查使用。 */
    private int schemaVersion = 1;

    /** 数据的索引映射，供按键查找或归并当前组件的数据。 */
    private Map<String, Object> data = new LinkedHashMap<>();

    /**
     * 校验当前呈现块契约的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的呈现块契约结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public PresentationBlockContract validate() {
        if (type == null || !type.matches("[a-z][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("presentation type is invalid");
        }
        if (schemaVersion <= 0)
            throw new IllegalArgumentException("schemaVersion must be positive");
        data = Map.copyOf(data == null ? Map.of() : data);
        return this;
    }
}
