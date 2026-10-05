package dev.horizen.agent.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 由宿主决定新一轮 Turn 是否允许从持久化历史恢复对话上下文。
 */
@AllArgsConstructor
@Data
@NoArgsConstructor
public class HistoryRecoveryScope {
    /** eligible的状态标记，用于选择当前组件的处理路径。 */
    private boolean eligible;
}
