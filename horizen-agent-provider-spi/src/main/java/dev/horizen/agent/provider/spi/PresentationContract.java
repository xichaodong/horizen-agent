package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 外部工具输出的结构化呈现内容，与工具成功状态分开表达。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PresentationContract {
    /** Schema的版本，供兼容或并发检查使用。 */
    private int schemaVersion = 1;

    /** 块集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
    private List<PresentationBlockContract> blocks = new ArrayList<>();

    /**
     * 校验当前呈现契约的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的呈现契约结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public PresentationContract validate() {
        if (schemaVersion != 1)
            throw new IllegalArgumentException("unsupported presentation schemaVersion");
        if (blocks == null || blocks.isEmpty() || blocks.size() > 32) {
            throw new IllegalArgumentException("presentation blocks must contain 1-32 items");
        }
        blocks.forEach(PresentationBlockContract::validate);
        blocks = List.copyOf(blocks);
        return this;
    }
}
