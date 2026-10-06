package dev.horizen.agent.adapter.agentscope.runtime;

import static dev.horizen.agent.adapter.agentscope.runtime.EventProvenanceMapper.*;
import static dev.horizen.agent.adapter.agentscope.runtime.EventTiming.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeEventFactory.*;
import static dev.horizen.agent.adapter.agentscope.runtime.RuntimeFailureClassifier.*;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;

import java.util.Map;

/**
 * 运行时事件来源映射边界：EventProvenanceMapper。
 */
final class EventProvenanceMapper {
    /**
     * 判断子Agent。
     *
     * @param source 待解析或转换的来源对象。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    static boolean isSubagent(AgentEvent source) {
        return source.getSource() != null && !source.getSource().isBlank();
    }

    /**
     * 生成当前操作所需的stepKey文本，供调用方继续处理。
     *
     * @param kind   当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。
     * @param source 待解析或转换的来源对象。
     * @param id     目标对象的标识。
     * @return 本次处理生成或读取的文本。
     */
    static String stepKey(String kind, AgentEvent source, String id) {
        String origin = isSubagent(source) ? source.getSource().trim() : "root";
        return kind + ":" + origin + ":" + id;
    }

    /**
     * 计算或取得本方法声明的结果，供当前EventProvenanceMapper处理步骤使用。
     *
     * @param target      本次转换、状态更新或内容写入的目标。
     * @param sourceEvent 当前事件来源关联映射器持有的来源事件对象，供相应处理步骤使用。
     * @return 本次操作返回的Agent运行时事件结果。
     */
    static AgentRuntimeEvent withProvenance(AgentRuntimeEvent target, AgentEvent sourceEvent) {
        if (!isSubagent(sourceEvent)) return target;
        String source = sourceEvent.getSource().trim();
        Map<String, Object> metadata = sourceEvent.getMetadata();
        String taskId = metadataText(metadata, AgentEvent.METADATA_TASK_ID);
        String parentSessionId = metadataText(metadata, AgentEvent.METADATA_PARENT_SESSION_ID);
        int separator = source.lastIndexOf('/');
        String agentId = source.substring(separator + 1);
        if (sourceEvent instanceof AgentStartEvent start
                && start.getName() != null
                && !start.getName().isBlank()) {
            agentId = start.getName().trim();
        }
        if (parentSessionId == null && separator > 0) {
            parentSessionId = source.substring(0, separator);
        }
        int depth = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '/') depth++;
        }
        target.setSource(source);
        target.setTaskId(taskId);
        target.setParentSessionId(parentSessionId);
        target.setAgentId(agentId);
        target.setDepth(Math.max(1, depth));
        return target;
    }

    /**
     * 生成当前操作所需的metadataText文本，供调用方继续处理。
     *
     * @param metadata 与当前对象关联的附加元数据，不替代领域状态或授权校验。
     * @param key      当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    static String metadataText(Map<String, Object> metadata, String key) {
        if (metadata == null) return null;
        Object value = metadata.get(key);
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().trim();
    }
}
