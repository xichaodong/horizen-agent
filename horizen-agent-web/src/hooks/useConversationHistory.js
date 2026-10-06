import {request as apiRequest} from '../api/client.js';
import {useEffect} from 'react';
import {useLatest} from './useLatest.js';
import {listMessages, listPresentations} from '../utils/chat.js';

/** 恢复当前会话的正式消息、过程事件和卡片，防止旧请求覆盖已切换会话的内容。 */
export function useConversationHistory({
                                           status,
                                           activeSessionId,
                                           sessions,
                                           updateSession,
                                           appendMessage,
                                           streamSequencesRef,
                                           applyTurnEvent,
                                       }) {
    const latest = useLatest({
        sessions,
        updateSession,
        appendMessage,
        streamSequencesRef,
        applyTurnEvent,
    });
    const targetSession = sessions.find((session) => session.id === activeSessionId);
    useEffect(() => {
        const {sessions, updateSession, appendMessage, streamSequencesRef, applyTurnEvent} =
            latest.current;
        if (!status.ready || !activeSessionId) return undefined;
        const target = sessions.find((session) => session.id === activeSessionId);
        if (!target?.persisted || target.historyLoaded) return undefined;
        const controller = new AbortController();
        apiRequest('/api/session/messages/query', {
            method: 'POST',
            body: JSON.stringify({sessionId: activeSessionId}),
            signal: controller.signal,
        })
            .then((response) => {
                if (!response.ok) throw new Error('History recovery failed');
                return response.json();
            })
            .then((payload) => {
                if (!payload) return;
                const restoredMessages = listMessages(payload).map((message) => ({
                    id: message.messageId,
                    role: message.role,
                    turnId: message.turnId,
                    content: message.content,
                    attachments: Array.isArray(message.attachments) ? message.attachments : [],
                    isStreaming: false,
                    sequence: message.sequence,
                    createdAt: message.createdAt,
                }));
                const restoredPresentations = listPresentations(payload).map((item) => ({
                    id: `presentation-${item.blockId}`,
                    role: 'presentation',
                    turnId: item.turnId,
                    presentation: item,
                }));
                const timelineEvents = Array.isArray(payload.timelineEvents)
                    ? payload.timelineEvents
                    : [];
                const currentTurnRecovery = payload.currentTurnRecovery;
                if (!timelineEvents.length) {
                    updateSession(activeSessionId, (session) => ({
                        ...session,
                        historyLoaded: false,
                        messages: [...restoredMessages, ...restoredPresentations],
                    }));
                }

                if (timelineEvents.length) {
                    const orderedTurnIds = [];
                    restoredMessages.forEach((message) => {
                        if (message.turnId && !orderedTurnIds.includes(message.turnId)) {
                            orderedTurnIds.push(message.turnId);
                        }
                    });
                    timelineEvents.forEach((item) => {
                        if (item.turnId && !orderedTurnIds.includes(item.turnId)) {
                            orderedTurnIds.push(item.turnId);
                        }
                    });
                    const maxTimelineSequence = timelineEvents.reduce(
                        (maximum, item) => Math.max(maximum, Number(item.sequence) || 0),
                        0
                    );
                    updateSession(activeSessionId, (session) => ({
                        ...session,
                        historyLoaded: false,
                        timelineSequence: maxTimelineSequence,
                        messages: [],
                    }));
                    orderedTurnIds.forEach((turnId) => {
                        const turnMessages = restoredMessages.filter(
                            (message) => message.turnId === turnId
                        );
                        const userMessages = turnMessages.filter(
                            (message) => message.role === 'user'
                        );
                        const assistantMessages = turnMessages.filter(
                            (message) => message.role === 'assistant'
                        );
                        if (userMessages.length) {
                            updateSession(activeSessionId, (session) => ({
                                ...session,
                                messages: [...session.messages, ...userMessages],
                            }));
                        }
                        const turnEvents = timelineEvents.filter((item) => item.turnId === turnId);
                        const assistantMessageId =
                            assistantMessages[0]?.id || `assistant-${turnId}`;
                        turnEvents.forEach((item) =>
                            applyTurnEvent(activeSessionId, assistantMessageId, item.event, {
                                animate: false,
                            })
                        );
                        const hasTerminalEvent = turnEvents.some((item) =>
                            ['done', 'error', 'cancelled'].includes(item.event?.type)
                        );
                        if (!hasTerminalEvent && assistantMessages.length) {
                            updateSession(activeSessionId, (session) => ({
                                ...session,
                                messages: [...session.messages, ...assistantMessages],
                            }));
                        }
                    });
                    if (restoredPresentations.length) {
                        updateSession(activeSessionId, (session) => {
                            const existing = new Set(session.messages.map((message) => message.id));
                            return {
                                ...session,
                                messages: [
                                    ...session.messages,
                                    ...restoredPresentations.filter(
                                        (message) => !existing.has(message.id)
                                    ),
                                ],
                            };
                        });
                    }
                }
                if (currentTurnRecovery?.turnId) {
                    const assistantMessageId = `assistant-${currentTurnRecovery.turnId}`;
                    streamSequencesRef.current.set(activeSessionId, 0);
                    updateSession(activeSessionId, (session) => ({
                        ...session,
                        currentTurnId: currentTurnRecovery.turnId,
                        currentAssistantMessageId: assistantMessageId,
                        executionStatus: 'running',
                        streamSequence: Number(currentTurnRecovery.lastEventSequence) || 0,
                    }));
                    (currentTurnRecovery.events || []).forEach((event) =>
                        applyTurnEvent(activeSessionId, assistantMessageId, event, {
                            animate: false,
                        })
                    );
                    streamSequencesRef.current.set(
                        activeSessionId,
                        Number(currentTurnRecovery.lastEventSequence) || 0
                    );
                }
                updateSession(activeSessionId, (session) => ({...session, historyLoaded: true}));
            })
            .catch((error) => {
                if (error.name !== 'AbortError') {
                    appendMessage(activeSessionId, {
                        id: `history-error-${activeSessionId}`,
                        role: 'system',
                        content: '会话历史恢复出现异常，请重新加载会话。',
                    });
                }
            });
        return () => controller.abort();
    }, [
        status.ready,
        activeSessionId,
        targetSession?.persisted,
        targetSession?.historyLoaded,
        latest,
    ]);
}
