package dev.horizen.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.json.JsonUtils;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 按顺序回放 Provider 调用；重复的相同调用分别消耗独立记录。
 */
public final class EvaluationFixture {
    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 当前功能模式，控制所选适配器或处理策略。
     */
    @Getter
    private final String mode;

    /**
     * 条目集合的索引映射，供按键查找或归并当前组件的数据。
     */
    private final List<Map<String, Object>> entries;

    /**
     * recorded的索引映射，供按键查找或归并当前组件的数据。
     */
    private final List<Map<String, Object>> recorded = new ArrayList<>();

    /**
     * 已被评测脚本匹配并消费的步骤或调用记录。
     */
    private final boolean[] consumed;

    /**
     * 用于EvaluationFixture内部处理的 failure 值；读写位置由该类型的方法限定。
     */
    private String failure;

    /**
     * 返回最近一次回放失败；与记录失败的写入方法共用实例锁，保证跨线程可见。
     */
    public synchronized String getFailure() {
        return failure;
    }

    /**
     * 回放Misses的索引映射，供按键查找或归并当前组件的数据。
     */
    private final List<Map<String, Object>> replayMisses = new ArrayList<>();

    /**
     * 存活限制集合的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<String, Integer> liveLimits = new LinkedHashMap<>();

    /**
     * 存活调用集合的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<String, Integer> liveCalls = new LinkedHashMap<>();

    /**
     * 读取存活限制集合的当前值。
     *
     * @param limits 限制集合的索引映射，供按键查找或归并当前组件的数据。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized void liveLimits(Map<String, Integer> limits) {
        limits.forEach(
                (tool, max) -> {
                    if (tool == null
                            || !tool.matches("[a-z][a-z0-9_]{0,127}")
                            || max == null
                            || max < 0
                            || max > 100)
                        throw new IllegalArgumentException("Invalid live tool call limit");
                });
        liveLimits.putAll(limits);
    }

    /**
     * 检查allowLiveCall对应的条件，供调用方选择后续处理分支。
     *
     * @param tool 当前评测样本使用的工具，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public synchronized boolean allowLiveCall(String tool) {
        Integer limit = liveLimits.get(tool);
        if (limit == null) return true;
        int used = liveCalls.getOrDefault(tool, 0);
        if (used >= limit) return false;
        liveCalls.put(tool, used + 1);
        return true;
    }

    /**
     * faults的索引映射，供按键查找或归并当前组件的数据。
     */
    private final List<Map<String, Object>> faults;

    /**
     * 当前评测故障注入所涉及的工具调用记录或次数。
     */
    private final int[] faultCalls;

    /**
     * 模型观察器的索引映射，供按键查找或归并当前组件的数据。
     */
    @Setter
    private Consumer<Map<String, Object>> modelObserver;

    /**
     * 完成当前操作的modelEvidence步骤，按实现更新相应状态或依赖。
     *
     * @param payload 负载的索引映射，供按键查找或归并当前组件的数据。
     */
    public void modelEvidence(Map<String, Object> payload) {
        if (modelObserver != null) modelObserver.accept(payload);
    }

    /**
     * 创建评测样本，初始化该组件所需的状态、配置或依赖。
     *
     * @param spec 规范的索引映射，供按键查找或归并当前组件的数据。
     */
    @SuppressWarnings("unchecked")
    public EvaluationFixture(Map<String, Object> spec) {
        this(spec, List.of());
    }

    /**
     * 创建评测样本，初始化该组件所需的状态、配置或依赖。
     *
     * @param spec   规范的索引映射，供按键查找或归并当前组件的数据。
     * @param faults faults的索引映射，供按键查找或归并当前组件的数据。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public EvaluationFixture(Map<String, Object> spec, List<Map<String, Object>> faults) {
        this.faults = List.copyOf(faults);
        this.faultCalls = new int[faults.size()];
        for (Map<String, Object> fault : faults) {
            if (!(fault.get("tool") instanceof String tool)
                    || tool.isBlank()
                    || !List.of("error", "result")
                    .contains(String.valueOf(fault.getOrDefault("kind", "error")))
                    || (fault.containsKey("times")
                    && (!(fault.get("times") instanceof Number n) || n.intValue() < 1)))
                throw new IllegalArgumentException("Invalid provider fault specification");
        }
        mode = String.valueOf(spec.getOrDefault("modeLabel", "LIVE"));
        if (!List.of("LIVE", "REPLAY", "RECORD", "CAPTURE_READONLY").contains(mode))
            throw new IllegalArgumentException("Unsupported fixture mode");
        entries =
                spec.get("entries") instanceof List<?> list
                        ? (List<Map<String, Object>>) list
                        : List.of();
        consumed = new boolean[entries.size()];
    }

    /**
     * 把当前输入编码为 JSON 文本，供协议输出或持久化保存使用。
     *
     * @param tool 当前评测样本使用的工具，供其处理与状态记录使用。
     * @return 本次操作返回的工具结果块结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized ToolResultBlock fault(String tool) {
        for (int i = 0; i < faults.size(); i++) {
            Map<String, Object> fault = faults.get(i);
            int limit = fault.get("times") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
            if (tool.equals(fault.get("tool")) && faultCalls[i] < limit) {
                faultCalls[i]++;
                if ("result".equals(fault.get("kind"))) {
                    try {
                        return ToolResultBlock.text(JSON.writeValueAsString(fault.get("result")));
                    } catch (Exception error) {
                        throw new IllegalArgumentException("Invalid fault result", error);
                    }
                }
                return ToolResultBlock.error(
                        String.valueOf(fault.getOrDefault("message", "Injected provider failure")));
            }
        }
        return null;
    }

    /**
     * 读取回放Misses的当前值。
     *
     * @return {@link #replayMisses} 中保存的值。
     */
    public synchronized List<Map<String, Object>> replayMisses() {
        return List.copyOf(replayMisses);
    }

    /**
     * 读取recorded的当前值。
     *
     * @return {@link #recorded} 中保存的值。
     */
    public synchronized List<Map<String, Object>> recorded() {
        if (!"CAPTURE_READONLY".equals(mode)) return List.copyOf(recorded);
        List<Map<String, Object>> complete = new ArrayList<>(entries);
        complete.addAll(recorded);
        return List.copyOf(complete);
    }

    /**
     * 计算或取得本方法声明的结果，供当前EvaluationFixture处理步骤使用。
     *
     * @param tool 当前评测样本使用的工具，供其处理与状态记录使用。
     * @param args 参数集合的索引映射，供按键查找或归并当前组件的数据。
     * @return 本次操作返回的工具结果块结果。
     */
    public synchronized ToolResultBlock replay(String tool, Map<String, Object> args) {
        return replay(tool, args, false);
    }

    /**
     * 计算或取得本方法声明的结果，供当前EvaluationFixture处理步骤使用。
     *
     * @param tool     当前评测样本使用的工具，供其处理与状态记录使用。
     * @param args     参数集合的索引映射，供按键查找或归并当前组件的数据。
     * @param readOnly 读取只读的状态标记，用于选择当前组件的处理路径。
     * @return 本次操作返回的工具结果块结果。
     */
    public synchronized ToolResultBlock replay(
            String tool, Map<String, Object> args, boolean readOnly) {
        ToolResultBlock result = tryReplay(tool, args, readOnly);
        if (result != null) return result;
        failure = "FIXTURE_MISS";
        if (replayMisses.size() < 128)
            replayMisses.add(Map.of("tool", tool, "args", JSON.convertValue(args, Map.class)));
        return ToolResultBlock.error("FIXTURE_MISS: no recorded provider result for " + tool);
    }

    /**
     * 计算或取得本方法声明的结果，供当前EvaluationFixture处理步骤使用。
     *
     * @param tool     当前评测样本使用的工具，供其处理与状态记录使用。
     * @param args     参数集合的索引映射，供按键查找或归并当前组件的数据。
     * @param readOnly 读取只读的状态标记，用于选择当前组件的处理路径。
     * @return 本次操作返回的工具结果块结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized ToolResultBlock tryReplay(
            String tool, Map<String, Object> args, boolean readOnly) {
        if (readOnly && "CAPTURE_READONLY".equals(mode)) {
            for (Map<String, Object> entry : recorded)
                if (tool.equals(entry.get("tool")) && matches(args, entry, true))
                    return result(entry);
        }
        // 优先精确匹配的记录；仅可信的只读契约允许使用明确放宽匹配条件的测试数据。
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < entries.size(); i++) {
                Map<String, Object> entry = entries.get(i);
                boolean relaxed =
                        (entry.get("ignoredArgs") instanceof List<?> ignored && !ignored.isEmpty())
                                || (entry.get("argumentDefaults") instanceof Map<?, ?> defaults
                                && !defaults.isEmpty());
                if ((pass == 0 && relaxed) || (pass == 1 && (!relaxed || !readOnly))) continue;
                if (!consumed[i]
                        && tool.equals(entry.get("tool"))
                        && matches(args, entry, readOnly)) {
                    consumed[i] = !(readOnly && Boolean.TRUE.equals(entry.get("reusable")));
                    if (!entry.containsKey("result"))
                        throw new IllegalArgumentException("Fixture entry missing result");
                    return result(entry);
                }
            }
        return null;
    }

    /**
     * 把当前输入编码为 JSON 文本，供协议输出或持久化保存使用。
     *
     * @param entry 条目的索引映射，供按键查找或归并当前组件的数据。
     * @return 本次操作返回的工具结果块结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static ToolResultBlock result(Map<String, Object> entry) {
        try {
            if (entry.get("resultState") != null && !"SUCCESS".equals(entry.get("resultState")))
                return ToolResultBlock.error(JSON.writeValueAsString(entry.get("result")));
            return ToolResultBlock.text(JSON.writeValueAsString(entry.get("result")));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid fixture result", error);
        }
    }

    /**
     * 检查是否匹配评测样本。
     *
     * @param args     参数集合的索引映射，供按键查找或归并当前组件的数据。
     * @param entry    条目的索引映射，供按键查找或归并当前组件的数据。
     * @param readOnly 读取只读的状态标记，用于选择当前组件的处理路径。
     * @return 本次检查是否通过或本次更新是否成功。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static boolean matches(
            Map<String, Object> args, Map<String, Object> entry, boolean readOnly) {
        var actual = JSON.valueToTree(args);
        var expected = JSON.valueToTree(entry.get("args"));
        if (readOnly
                && entry.get("argumentDefaults") instanceof Map<?, ?> defaults
                && !defaults.isEmpty()) {
            if (!actual.isObject() || !expected.isObject()) return false;
            defaults.forEach(
                    (key, value) -> {
                        if (!(key instanceof String name)
                                || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
                            throw new IllegalArgumentException("Invalid fixture argument default");
                        if (!actual.has(name))
                            ((ObjectNode) actual).set(name, JSON.valueToTree(value));
                        if (!expected.has(name))
                            ((ObjectNode) expected).set(name, JSON.valueToTree(value));
                    });
        }
        if (readOnly && entry.get("ignoredArgs") instanceof List<?> ignored && !ignored.isEmpty()) {
            if (!actual.isObject() || !expected.isObject()) return false;
            for (Object raw : ignored) {
                if (!(raw instanceof String name) || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
                    throw new IllegalArgumentException("Invalid fixture ignored argument");
                ((ObjectNode) actual).remove(name);
                ((ObjectNode) expected).remove(name);
            }
        }
        return Objects.equals(normalize(actual), normalize(expected));
    }

    /**
     * 规范化评测样本。
     *
     * @param node 当前评测样本持有的节点对象，供相应处理步骤使用。
     * @return 本次操作返回的JSON节点结果。
     */
    private static JsonNode normalize(JsonNode node) {
        if (node.isNumber()) return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        if (node.isObject()) {
            var object = JSON.createObjectNode();
            node.fields()
                    .forEachRemaining(
                            entry -> object.set(entry.getKey(), normalize(entry.getValue())));
            return object;
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(value -> array.add(normalize(value)));
            return array;
        }
        return node;
    }

    /**
     * 记录评测样本。
     *
     * @param tool   当前评测样本使用的工具，供其处理与状态记录使用。
     * @param args   参数集合的索引映射，供按键查找或归并当前组件的数据。
     * @param result 本次处理已有的结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public synchronized void record(String tool, Map<String, Object> args, ToolResultBlock result) {
        if (recorded.size() >= 10000)
            throw new IllegalStateException("Fixture recording capacity exceeded");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tool", tool);
        entry.put("args", JSON.convertValue(args, Map.class));
        Object value;
        try {
            value =
                    JSON.readValue(
                            result.getOutput().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .map(TextBlock::getText)
                                    .collect(Collectors.joining()),
                            Object.class);
        } catch (Exception ignored) {
            value =
                    result.getOutput().stream()
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .collect(Collectors.joining());
        }
        entry.put("result", value);
        entry.put("resultState", result.getState() == null ? "SUCCESS" : result.getState().name());
        entry.put("ts", System.currentTimeMillis());
        if ("CAPTURE_READONLY".equals(mode)) entry.put("reusable", true);
        recorded.add(entry);
    }
}
