package dev.horizen.agent.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.evaluation.model.EvaluationCase;

import java.util.*;

/**
 * 评测用例 JSON 的统一校验与解码边界。
 */
final class EvaluationCaseParser {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private EvaluationCaseParser() {
    }

    /**
     * 解析评测用例Parser。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的评测用例结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static EvaluationCase parse(EvaluationProtocol.Start request) {
        validate(request);
        Map<?, ?> layer = (Map<?, ?>) ((Map<?, ?>) request.getInput()).get("case");
        var steps =
                ((List<?>) layer.get("steps"))
                        .stream()
                        .map(raw -> JSON.convertValue(raw, EvaluationProtocol.Step.class))
                        .toList();
        var interactions =
                (layer.get("interactions") instanceof List<?> values ? values : List.of())
                        .stream()
                        .map(
                                raw ->
                                        JSON.convertValue(
                                                raw, EvaluationProtocol.Interaction.class))
                        .toList();
        List<Map<String, Object>> faults = new ArrayList<>();
        for (Object raw : layer.get("faults") instanceof List<?> values ? values : List.of())
            faults.add(JSON.convertValue(raw, new TypeReference<Map<String, Object>>() {
            }));
        Map<String, Integer> limits = new LinkedHashMap<>();
        if (layer.get("maxLiveCalls") instanceof Map<?, ?> values)
            values.forEach(
                    (tool, count) -> {
                        if (!(tool instanceof String name)
                                || !(count instanceof Number n)
                                || n.doubleValue() != n.intValue())
                            throw new IllegalArgumentException(
                                    "maxLiveCalls must map tool names to integer limits");
                        limits.put(name, n.intValue());
                    });
        return new EvaluationCase(
                List.copyOf(steps),
                List.copyOf(interactions),
                List.copyOf(faults),
                Map.copyOf(limits));
    }

    /**
     * 校验当前评测用例Parser的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @param r 当前评测用例Parser持有的r对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void validate(EvaluationProtocol.Start r) {
        if (r == null
                || r.getProtocolVersion() != 1
                || r.getExecutionId() == null
                || !r.getExecutionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new IllegalArgumentException("Invalid evaluation protocol or executionId");
        if (r.getTimeoutMs() < 1000 || r.getTimeoutMs() > 1800000)
            throw new IllegalArgumentException("timeoutMs must be 1000..1800000");
        if (!(r.getInput() instanceof Map<?, ?> input)
                || !(input.get("case") instanceof Map<?, ?> layer)
                || !(layer.get("steps") instanceof List<?> steps)
                || steps.isEmpty()
                || steps.size() > 100)
            throw new IllegalArgumentException("input.case.steps is required");
        for (Object step : steps)
            if (!(step instanceof Map<?, ?> map)
                    || !(map.get("userInput") instanceof String s)
                    || s.isBlank())
                throw new IllegalArgumentException("Each step requires userInput");
        if (layer.containsKey("faults")
                && (!(layer.get("faults") instanceof List<?> faults) || faults.size() > 100))
            throw new IllegalArgumentException("faults must be an array with at most 100 entries");
        if (r.getFixture() == null) throw new IllegalArgumentException("fixture must be an object");
        if (layer.get("interactions") instanceof List<?> interactions) {
            if (interactions.size() > 100)
                throw new IllegalArgumentException("Too many scripted interactions");
            for (Object raw : interactions) {
                EvaluationProtocol.Interaction interaction =
                        JSON.convertValue(raw, EvaluationProtocol.Interaction.class);
                if (!List.of("approval", "ask_user").contains(interaction.getType())
                        || interaction.getDelayMs() < 0
                        || interaction.getDelayMs() > r.getTimeoutMs())
                    throw new IllegalArgumentException("Invalid scripted interaction");
                if (interaction.isOptional() && !"ask_user".equals(interaction.getType()))
                    throw new IllegalArgumentException(
                            "Only questions may be optional; approval must remain explicit");
                if ("approval".equals(interaction.getType())
                        && (interaction.getApproved() == null
                        || interaction.getToolNames() == null
                        || interaction.getToolNames().isEmpty()))
                    throw new IllegalArgumentException(
                            "Approval script requires explicit toolNames and approved");
            }
        } else if (layer.containsKey("interactions"))
            throw new IllegalArgumentException("interactions must be an array");
    }
}
