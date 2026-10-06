import {request as apiRequest} from '../api/client.js';
import {createId} from '../utils/chat.js';

/** 管理用户消息发送、显式取消与手动重试；取消已提交与实际执行停止分开判断。 */
export function useChatActions({composer, session, execution, artifacts}) {
    const {inputValue, setInputValue, inputRef} = composer;
    const {activeSession, running, messages, updateSession, appendMessage, upsertActivity} =
        session;
    const {
        recoveryJson,
        applyTurnEvent,
        executionFailureEvent,
        streams,
        streamSequencesRef,
        followSessionResponse,
    } = execution;
    const {attachments, setAttachments} = artifacts;
    const handleStop = async () => {
        if (!activeSession) return;
        try {
            let turnId = activeSession.currentTurnId;
            if (!turnId)
                turnId = (await recoveryJson('/api/session/query', activeSession.id)).currentTurnId;
            if (!turnId) throw new Error('turn is unknown');
            const response = await apiRequest('/api/session/cancel', {
                method: 'POST',
                body: JSON.stringify({sessionId: activeSession.id, expectedTurnId: turnId}),
            });
            if (!response.ok) throw new Error('cancel failed');
            const execution = await response.json();
            const assistantMessageId =
                activeSession.currentAssistantMessageId || `assistant-${turnId}`;
            if (['cancelled', 'failed', 'timed_out'].includes(execution.status)) {
                applyTurnEvent(
                    activeSession.id,
                    assistantMessageId,
                    executionFailureEvent(execution)
                );
                streams.stop(activeSession.id);
            } else {
                // 取消请求被接受后仍需继续观察，直到确认执行已取消。
                updateSession(activeSession.id, (session) => ({
                    ...session,
                    executionStatus: execution.status,
                }));
                upsertActivity(
                    activeSession.id,
                    assistantMessageId,
                    `cancel-${turnId}`,
                    {activityType: 'notice'},
                    (message) => ({
                        ...message,
                        title: '取消请求已提交',
                        content: '正在确认任务是否已停止。',
                        status: 'unknown',
                    })
                );
            }
        } catch (error) {
            appendMessage(activeSession.id, {
                id: createId(),
                role: 'system',
                content: '取消请求出现异常，尚未确认任务已停止。',
            });
        }
    };

    const sendMessage = async (override, overrideArtifacts) => {
        const text = String(override ?? inputValue).trim();
        if (!text || running || !activeSession) {
            return;
        }
        const sessionId = activeSession.id;
        const selectedArtifacts = overrideArtifacts === undefined ? attachments : overrideArtifacts;
        const userMessage = {
            id: createId(),
            role: 'user',
            content: text,
            attachments: selectedArtifacts,
        };
        updateSession(sessionId, (session) => ({
            ...session,
            title: session.title === '新对话' ? text.slice(0, 20) : session.title,
            updatedAt: Date.now(),
            messages: [...session.messages, userMessage],
        }));
        setInputValue('');
        setAttachments([]);
        const controller = streams.begin(sessionId);
        const assistantMessageId = createId();
        streamSequencesRef.current.set(sessionId, 0);
        updateSession(sessionId, (session) => ({...session, streamSequence: 0}));
        try {
            const response = await apiRequest('/api/chat/stream', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Accept: 'text/event-stream',
                },
                body: JSON.stringify({
                    sessionId,
                    message: text,
                    artifactIds: selectedArtifacts.map((item) => item.artifactId),
                }),
                signal: controller.signal,
            });
            if (!response.ok) {
                const payload = await response.json().catch(() => ({}));
                throw new Error(payload.error || '请求处理出现异常，当前结果尚未确认。');
            }
            updateSession(sessionId, (session) => ({
                ...session,
                persisted: true,
                historyLoaded: true,
            }));
            await followSessionResponse(
                sessionId,
                assistantMessageId,
                response,
                controller.signal,
                null,
                activeSession.currentTurnId
            );
        } catch (error) {
            if (error.name !== 'AbortError') {
                appendMessage(sessionId, {
                    id: createId(),
                    role: 'system',
                    content: error.message || 'Agent 调用失败',
                });
            }
        } finally {
            streams.finish(sessionId, controller);
            window.setTimeout(() => inputRef.current?.focus(), 0);
        }
    };

    const handleKeyDown = (event) => {
        if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault();
            sendMessage();
        }
    };

    const retryLastMessage = () => {
        const lastUserMessage = [...messages].reverse().find((message) => message.role === 'user');
        if (lastUserMessage) {
            sendMessage(lastUserMessage.content, lastUserMessage.attachments || []);
        }
    };
    return {handleStop, sendMessage, handleKeyDown, retryLastMessage};
}
