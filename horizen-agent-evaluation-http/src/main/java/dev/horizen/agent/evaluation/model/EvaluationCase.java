package dev.horizen.agent.evaluation.model;

import dev.horizen.agent.evaluation.EvaluationProtocol;

import lombok.Value;

import java.util.List;
import java.util.Map;

/** 经过校验的执行输入；未校验 JSON 值不能进入评测执行循环。 */
@Value
public class EvaluationCase {
    /** steps的有序集合，保留当前组件处理或协议输出所需的顺序。 */
    List<EvaluationProtocol.Step> steps;

    /** interactions的有序集合，保留当前组件处理或协议输出所需的顺序。 */
    List<EvaluationProtocol.Interaction> interactions;

    /** faults的索引映射，供按键查找或归并当前组件的数据。 */
    List<Map<String, Object>> faults;

    /** 最大存活调用集合的索引映射，供按键查找或归并当前组件的数据。 */
    Map<String, Integer> maxLiveCalls;
}
