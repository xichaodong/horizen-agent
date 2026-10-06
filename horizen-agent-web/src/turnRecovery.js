const SETTLED_TYPES = new Set([
    'done',
    'error',
    'cancelled',
    'approval_required',
    'ask_user_required',
]);

/** 只将主执行的结束事件视为整个执行结束，子 Agent 的结束不会提前关闭主流。 */
export const isTurnSettled = (event) => !event.source && SETTLED_TYPES.has(event.type);

/** 按 UTF-8 增量解码 SSE 数据并逐条交给消费者，保留跨读取边界的不完整片段。 */
export async function readEventStream(response, onEvent) {
    if (!response.ok) {
        const payload = await response.json().catch(() => ({}));
        throw new Error(payload.error || '请求处理出现异常，当前结果尚未确认。');
    }
    if (!response.body) throw new Error('连接异常，未收到执行结果。');
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let settled = false;
    const consume = (flush) => {
        buffer = buffer.replace(/\r\n/g, '\n');
        const frames = buffer.split('\n\n');
        buffer = flush ? '' : frames.pop();
        for (const frame of frames) {
            const data = frame
                .split('\n')
                .filter((line) => line.startsWith('data:'))
                .map((line) => line.slice(5).trimStart())
                .join('\n');
            if (!data || settled) continue;
            const event = JSON.parse(data);
            onEvent(event);
            settled = isTurnSettled(event);
        }
    };
    try {
        while (!settled) {
            const {value, done} = await reader.read();
            buffer += decoder.decode(value, {stream: !done});
            consume(done);
            if (done) break;
        }
        return settled;
    } finally {
        // 仅释放 HTTP 观察者，服务端执行仍独立继续。
        try {
            await reader.cancel();
        } catch (_) {
            /* 传输连接已经关闭 */
        }
        reader.releaseLock();
    }
}

const abortableDelay = (milliseconds, signal) =>
    new Promise((resolve, reject) => {
        const finish = () => {
            signal?.removeEventListener('abort', abort);
            resolve();
        };
        const abort = () => {
            clearTimeout(timer);
            signal?.removeEventListener('abort', abort);
            reject(new DOMException('Aborted', 'AbortError'));
        };
        const timer = setTimeout(finish, milliseconds);
        signal?.addEventListener('abort', abort, {once: true});
        if (signal?.aborted) abort();
    });

/** 把持久执行的失败、取消或超时状态转换成可展示事件。 */
export function executionFailureEvent(execution) {
    const cancelled = execution.status === 'cancelled';
    const timedOut = execution.status === 'timed_out';
    const limited = execution.failureCode === 'MAX_ITERATIONS_REACHED';
    return {
        type: cancelled ? 'cancelled' : 'error',
        id: execution.currentTurnId,
        title: cancelled ? '已取消' : timedOut ? '执行超时' : limited ? '达到执行限制' : '执行异常',
        text: cancelled
            ? '本轮执行已取消。'
            : timedOut
                ? '本轮执行超过时间限制，已停止。'
                : limited
                    ? '本轮已达到执行次数限制，尚未确认任务完成。'
                    : '本轮执行出现异常，已停止。',
        status: execution.status,
        details: JSON.stringify({errorCode: execution.failureCode || 'EXECUTION_ERROR'}),
    };
}

// 仅恢复事件传输，不重新提交用户请求或重复执行业务工具。
/** 跟踪原执行的事件流；断开后查询事实状态与正式历史，再按游标恢复，避免重新提交用户任务。 */
export async function followTurnStream({
                                           response,
                                           expectedTurnId,
                                           previousTurnId,
                                           onEvent,
                                           queryExecution,
                                           subscribe,
                                           loadHistory,
                                           onConnection = () => {
                                           },
                                           signal,
                                           delay = abortableDelay,
                                       }) {
    let turnId = expectedTurnId;
    let attempt = 0;
    let recovering = false;
    const deliver = (event) => {
        if (!event.source && event.type === 'turn_start') turnId = event.id;
        onEvent(event);
    };
    while (true) {
        if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
        try {
            if (response && (await readEventStream(response, deliver))) {
                if (recovering) onConnection('restored');
                return;
            }
        } catch (error) {
            if (signal?.aborted || error.name === 'AbortError') throw error;
        }
        response = null;
        if (!recovering) {
            recovering = true;
            onConnection('recovering');
        }
        try {
            const execution = await queryExecution();
            const current = execution.currentTurnId;
            if (
                !current ||
                (turnId && current !== turnId) ||
                (!turnId && current === previousTurnId)
            ) {
                onConnection('unknown');
                return;
            }
            turnId = current;
            if (['failed', 'timed_out', 'cancelled'].includes(execution.status)) {
                // 即使时间线或 Redis 恢复失败，持久化 Turn 状态仍可使用。
                deliver(executionFailureEvent(execution));
                try {
                    const history = await loadHistory();
                    const failure = (history.timelineEvents || [])
                        .filter((item) => item.turnId === turnId)
                        .map((item) => item.event)
                        .findLast(
                            (event) => !event.source && ['error', 'cancelled'].includes(event.type)
                        );
                    if (failure) deliver(failure);
                } catch (error) {
                    if (signal?.aborted || error.name === 'AbortError') throw error;
                }
                onConnection('restored');
                return;
            }
            if (execution.status === 'running' || execution.status === 'cancelling') {
                response = await subscribe(turnId);
            } else if (
                ['completed', 'waiting_approval', 'waiting_ask_user'].includes(execution.status)
            ) {
                const history = await loadHistory();
                const events = (history.timelineEvents || [])
                    .filter((item) => item.turnId === turnId)
                    .map((item) => item.event);
                const expected =
                    execution.status === 'completed'
                        ? 'done'
                        : execution.status === 'waiting_approval'
                            ? 'approval_required'
                            : 'ask_user_required';
                const settled = events.findLast(
                    (event) => !event.source && event.type === expected
                );
                for (const event of events.filter((event) => !isTurnSettled(event))) deliver(event);
                if (settled) deliver(settled);
                else if (execution.status === 'completed') {
                    const reply = (history.messages || []).findLast(
                        (message) => message.turnId === turnId && message.role === 'assistant'
                    );
                    if (!reply) throw new Error('Completed reply is not available');
                    deliver({type: 'done', id: turnId, status: 'success', text: reply.content});
                } else {
                    throw new Error('Pending interaction is not available');
                }
                onConnection('restored');
                return;
            } else {
                onConnection('unknown');
                return;
            }
        } catch (error) {
            if (signal?.aborted || error.name === 'AbortError') throw error;
            // 重复传输失败只展示一次重连提示。
        }
        await delay(Math.min(500 * 2 ** Math.min(attempt++, 4), 5000), signal);
    }
}
