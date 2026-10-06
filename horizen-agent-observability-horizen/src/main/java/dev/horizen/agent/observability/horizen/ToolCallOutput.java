package dev.horizen.agent.observability.horizen;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单一职责的追踪采集器，由单次调用独占。
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ToolCallOutput {
    /**
     * 本组件使用的 {@code HorizenTraceRun} 状态或依赖，用于 run 的处理。
     */
    private final HorizenTraceRun run;

    /**
     * 当前工具调用输出的定位标识。
     */
    final String id;

    /**
     * 当前工具调用输出的名称，用于目录、调用或展示中的识别。
     */
    final String name;

    /**
     * 工具调用参数，按工具目录中的输入 Schema 解释。
     */
    final StringBuilder arguments = new StringBuilder();

    /**
     * 计算或取得本方法声明的结果，供当前ToolCallOutput处理步骤使用。
     *
     * @return 本次操作返回的对象结果。
     */
    Object view() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        if (run.config.isCaptureContent()) result.put("arguments", arguments.toString());
        else result.put("argumentsCaptured", false);
        return result;
    }
}
