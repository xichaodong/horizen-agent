// 完整持久化快照替换对应工具片段，旧版事件仍按追加方式处理。
/** 从完整工具事实负载中读取历史快照。 */
export function toolSnapshot(details) {
    try {
        const value = typeof details === 'string' ? JSON.parse(details) : details;
        const snapshot = value?.toolCallSnapshot;
        return snapshot?.version === 1 ? snapshot : null;
    } catch (_) {
        return null;
    }
}

/** 收敛一条工具过程记录，保留已经收到的输入与输出，不把缺失字段解释为清空。 */
export function finishTool(step, event) {
    const snapshot = toolSnapshot(event.details);
    return {
        ...step,
        ...(snapshot && typeof snapshot.input === 'string' ? { input: snapshot.input } : {}),
        ...(snapshot && typeof snapshot.output === 'string' ? { output: snapshot.output } : {}),
        outputSnapshot: true,
        status: event.status || 'success',
        durationMs: event.durationMs,
        content:
            event.text ||
            (['error', 'failed', 'interrupted', 'denied'].includes(event.status)
                ? event.status === 'denied'
                    ? '工具操作未获授权，未能完成。'
                    : '工具执行出现异常，正在尝试继续处理。'
                : step.content),
    };
}

/** 将工具输出增量合并到原步骤的输出。 */
export function appendToolOutput(step, details) {
    return {
        ...step,
        output: `${step.outputSnapshot ? '' : step.output || ''}${details || ''}`,
        outputSnapshot: false,
    };
}

/** 结束子 Agent 记录及其尚未结束的步骤，避免把未完成步骤标为成功。 */
export function finishSubagent(execution, status, forceStatus = false) {
    const unfinished = ['success', 'ended'].includes(status) ? 'interrupted' : status;
    return {
        ...execution,
        status: forceStatus || execution.status === 'running' ? status : execution.status,
        steps: (execution.steps || []).map((step) =>
            step.status === 'running' ? { ...step, status: unfinished } : step
        ),
    };
}
