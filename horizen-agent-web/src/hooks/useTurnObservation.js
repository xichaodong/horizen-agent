import {request as apiRequest} from '../api/client.js';
import {useEffect, useMemo} from 'react';
import {executionFailureEvent} from '../turnRecovery.js';
import {createId} from '../utils/chat.js';
import {useLatest} from './useLatest.js';

/** 在正式历史恢复完成后观察当前执行，重连时核对执行标识，并在切换会话或卸载时释放旧订阅。 */
export function useTurnObservation(options) {
    const {messages, activeSessionId, status, activeSession, streams} = options;
    const latest = useLatest(options);
    const hasRunningSubtask = useMemo(
        () =>
            messages.some((message) => message.role === 'subtask' && message.status === 'running'),
        [messages]
    );

    useEffect(() => {
        if (!activeSessionId || !hasRunningSubtask) return undefined;
        latest.current.loadSubtasks(activeSessionId);
        const timer = window.setInterval(() => latest.current.loadSubtasks(activeSessionId), 2000);
        return () => window.clearInterval(timer);
    }, [activeSessionId, hasRunningSubtask, latest]);

    useEffect(() => {
        if (!status.ready || !activeSessionId) {
            return undefined;
        }
        const {
            sessionsRef,
            updateSession,
            applyTurnEvent,
            followSessionResponse,
            streamSequencesRef,
            turnIdsRef,
            showConnectionNotice,
        } = latest.current;
        const initialSession = sessionsRef.current.find((item) => item.id === activeSessionId);
        // 刷新后先完成 MySQL 历史恢复，再回放当前 Turn 的 Redis 增量，避免两条异步链互相覆盖。
        if (initialSession?.persisted && !initialSession.historyLoaded) {
            return undefined;
        }
        let detached = false;
        const controller = streams.begin(activeSessionId, false);
        let retryTimer = null;
        let retryAttempt = 0;
        const reconnect = async () => {
            let retrying = false;
            try {
                const queryResponse = await apiRequest('/api/session/query', {
                    method: 'POST',
                    body: JSON.stringify({sessionId: activeSessionId}),
                    signal: controller.signal,
                });
                if (!queryResponse.ok) throw new Error('查询执行状态失败');
                const execution = await queryResponse.json();
                if (!execution.currentTurnId) {
                    streams.finish(activeSessionId, controller);
                    return;
                }
                if (!['running', 'cancelling'].includes(execution.status)) {
                    const session = sessionsRef.current.find((item) => item.id === activeSessionId);
                    const assistantMessageId =
                        session?.currentAssistantMessageId ||
                        `assistant-${execution.currentTurnId}`;
                    if (['failed', 'timed_out', 'cancelled'].includes(execution.status)) {
                        if (
                            !session?.messages.some(
                                (message) =>
                                    message.turnId === assistantMessageId &&
                                    message.activityType === 'error'
                            )
                        ) {
                            applyTurnEvent(
                                activeSessionId,
                                assistantMessageId,
                                executionFailureEvent(execution)
                            );
                        }
                    } else if (
                        session?.currentTurnId === execution.currentTurnId &&
                        ['running', 'cancelling', 'unknown'].includes(session.executionStatus)
                    ) {
                        await followSessionResponse(
                            activeSessionId,
                            assistantMessageId,
                            null,
                            controller.signal,
                            execution.currentTurnId
                        );
                    }
                    streams.finish(activeSessionId, controller);
                    return;
                }
                const session = sessionsRef.current.find((item) => item.id === activeSessionId);
                const sameTurn = session?.currentTurnId === execution.currentTurnId;
                const eventCursor = sameTurn ? Number(session?.streamSequence) || 0 : 0;
                const assistantMessageId =
                    sameTurn && session.currentAssistantMessageId
                        ? session.currentAssistantMessageId
                        : createId();
                if (!sameTurn) streamSequencesRef.current.set(activeSessionId, 0);
                turnIdsRef.current.set(activeSessionId, execution.currentTurnId);
                updateSession(activeSessionId, (current) => ({
                    ...current,
                    currentTurnId: execution.currentTurnId,
                    currentAssistantMessageId: assistantMessageId,
                    executionStatus: execution.status,
                    streamSequence: eventCursor,
                    messages:
                        eventCursor > 0
                            ? current.messages
                            : current.messages.filter(
                                (message) => message.turnId !== assistantMessageId
                            ),
                }));
                streams.markRunning(activeSessionId, controller);
                const response = await apiRequest('/api/session/subscribe', {
                    method: 'POST',
                    headers: {Accept: 'text/event-stream'},
                    body: JSON.stringify({
                        sessionId: activeSessionId,
                        expectedTurnId: execution.currentTurnId,
                        afterTimelineSequence: session?.timelineSequence || 0,
                        afterEventSequence: eventCursor,
                    }),
                    signal: controller.signal,
                });
                await followSessionResponse(
                    activeSessionId,
                    assistantMessageId,
                    response,
                    controller.signal,
                    execution.currentTurnId
                );
            } catch (error) {
                if (!detached && error.name !== 'AbortError') {
                    retrying = true;
                    const session = sessionsRef.current.find((item) => item.id === activeSessionId);
                    showConnectionNotice(
                        activeSessionId,
                        session?.currentAssistantMessageId || `recovery-${activeSessionId}`,
                        'recovering'
                    );
                }
            } finally {
                if (retrying && !detached) {
                    const delay = Math.min(500 * 2 ** retryAttempt, 5000);
                    retryAttempt += 1;
                    retryTimer = window.setTimeout(reconnect, delay);
                } else if (!detached) {
                    streams.finish(activeSessionId, controller);
                }
            }
        };
        reconnect();
        return () => {
            detached = true;
            controller.abort();
            if (retryTimer !== null) window.clearTimeout(retryTimer);
            streams.finish(activeSessionId, controller);
        };
    }, [status.ready, activeSessionId, activeSession?.historyLoaded, streams, latest]);
}
