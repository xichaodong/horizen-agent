package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.sandbox.SandboxException.ExecTimeoutException;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 运行时失败分类边界：RuntimeFailureClassifier。
 */
final class RuntimeFailureClassifier {
    /**
     * 生成当前操作所需的stopFailureCode文本，供调用方继续处理。
     *
     * @param reason 当前运行时失败分类器持有的原因对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    static String stopFailureCode(GenerateReason reason) {
        if (reason == null) return "UNKNOWN_STOP_REASON";
        return switch (reason) {
            case MODEL_STOP, STRUCTURED_OUTPUT -> null;
            case MAX_ITERATIONS -> "MAX_ITERATIONS_REACHED";
            case INTERRUPTED -> "AGENT_INTERRUPTED";
            case REASONING_STOP_REQUESTED, ACTING_STOP_REQUESTED, ALL_TOOLS_DENIED -> "AGENT_STOPPED";
            case TOOL_CALLS, PERMISSION_ASKING, TOOL_SUSPENDED, MIDDLEWARE_STOP_REQUESTED -> "UNEXPECTED_STOP_REASON";
        };
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeFailureClassifier处理步骤使用。
     *
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     * @return 本次操作返回的Generate原因结果。
     */
    static GenerateReason stopReason(Msg message) {
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null || !metadata.containsKey(Msg.METADATA_GENERATE_REASON)) {
            return message.getGenerateReason();
        }
        Object raw = metadata.get(Msg.METADATA_GENERATE_REASON);
        if (raw instanceof GenerateReason reason) return reason;
        if (raw instanceof String value) {
            try {
                return GenerateReason.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 判断超时。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof ExecTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 生成当前操作所需的errorCode文本，供调用方继续处理。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次处理生成或读取的文本。
     */
    static String errorCode(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    /**
     * 生成当前操作所需的executionFailureCode文本，供调用方继续处理。
     *
     * @param stepStarts 步骤启动次数的索引映射，供按键查找或归并当前组件的数据。
     * @return 本次处理生成或读取的文本。
     */
    static String executionFailureCode(Map<String, Long> stepStarts) {
        if (stepStarts.keySet().stream().anyMatch(key -> key.startsWith("tool:"))) {
            return "TOOL_FAILED";
        }
        if (stepStarts.keySet().stream().anyMatch(key -> key.startsWith("model:"))) {
            return "MODEL_FAILED";
        }
        return "RUNTIME_FAILED";
    }
}
