package dev.horizen.agent.web.api;

import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import java.util.*;

/**
 * 合并持久化事实与实时片段，不重复回放已被完整工具快照覆盖的片段。
 */
final class ToolHistoryRecovery {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ToolHistoryRecovery() {
    }

    /**
     * 计算或取得本方法声明的结果，供当前ToolHistoryRecovery处理步骤使用。
     *
     * @param snapshot 当前工具历史恢复持有的快照对象，供相应处理步骤使用。
     * @param timeline 时间线的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param mapper   本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @return 本次处理得到的结果集合。
     */
    static List<AgentRuntimeEvent> uncovered(
            RedisTurnEventBridge.EventSnapshot snapshot,
            List<SessionApi.TimelineEventResponse> timeline,
            AgentApiMapper mapper) {
        Set<Long> covered = new HashSet<>();
        Set<Long> completeTools = new HashSet<>();
        for (var item : timeline) {
            covered.add(item.getSequence());
            var event = item.getEvent();
            if (!"tool_end".equals(event.getType()) || event.getDetails() == null) continue;
            try {
                Object value = mapper.jsonMap(event.getDetails()).get("toolCallSnapshot");
                if (value instanceof Map<?, ?> data
                        && Integer.valueOf(1).equals(data.get("version"))) {
                    completeTools.add(item.getSequence());
                }
            } catch (RuntimeException ignored) {
                // 旧版 tool_end 行没有完整快照，仍需其对应片段。
            }
        }
        Map<String, Long> completedThrough = new HashMap<>();
        Map<Long, Long> references = snapshot.getTimelineReferences();
        for (var event : snapshot.getEvents()) {
            Long reference =
                    event.getStreamSequence() == null
                            ? null
                            : references.get(event.getStreamSequence());
            if (event.getType() == AgentRuntimeEvent.Type.TOOL_COMPLETED
                    && completeTools.contains(reference)) {
                completedThrough.merge(key(event), event.getStreamSequence(), Math::max);
            }
        }
        return snapshot.getEvents().stream()
                .filter(
                        event -> {
                            Long reference =
                                    event.getStreamSequence() == null
                                            ? null
                                            : references.get(event.getStreamSequence());
                            if (reference != null && covered.contains(reference)) return false;
                            if (isToolEvent(event) && event.getStreamSequence() != null) {
                                Long through = completedThrough.get(key(event));
                                if (through != null && event.getStreamSequence() <= through)
                                    return false;
                            }
                            return true;
                        })
                .toList();
    }

    /**
     * 判断工具事件。
     *
     * @param event 当前工具历史恢复持有的事件对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean isToolEvent(AgentRuntimeEvent event) {
        return event.getType() == AgentRuntimeEvent.Type.TOOL_STARTED
                || event.getType() == AgentRuntimeEvent.Type.TOOL_INPUT_DELTA
                || event.getType() == AgentRuntimeEvent.Type.TOOL_OUTPUT_DELTA
                || event.getType() == AgentRuntimeEvent.Type.TOOL_COMPLETED;
    }

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param event 当前工具历史恢复持有的事件对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String key(AgentRuntimeEvent event) {
        return (event.getSource() == null ? "" : event.getSource())
                + '\0'
                + (event.getTaskId() == null ? "" : event.getTaskId())
                + '\0'
                + event.getId();
    }
}
