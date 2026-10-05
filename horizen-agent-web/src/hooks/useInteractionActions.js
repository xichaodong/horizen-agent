import { post } from '../api/client.js';
import { createId } from '../utils/chat.js';
/** 提交审批和澄清决定后重新观察原执行；已提交决定不会因观察连接失败而回退。 */
export function useInteractionActions({ session, execution }) {
    const { activeSessionId, activeSession, updateSession, appendMessage } = session;
    const { streams, turnIdsRef, streamSequencesRef, followSessionResponse } = execution;
    const resolve = async (message, url, body, resolvedStatus) => {
        const sessionId = activeSessionId;
        const controller = streams.begin(sessionId);
        let committed = false;
        const updateCard = (status) =>
            updateSession(sessionId, (value) => ({
                ...value,
                messages: value.messages.map((item) =>
                    item.id === message.id ? { ...item, status } : item
                ),
            }));
        updateCard('submitting');
        try {
            const response = await post(url, body, { signal: controller.signal });
            if (!response.ok) throw new Error('交互提交失败');
            const result = await response.json();
            committed = true;
            updateCard(resolvedStatus);
            streamSequencesRef.current.set(sessionId, 0);
            if (result.currentTurnId) turnIdsRef.current.set(sessionId, result.currentTurnId);
            updateSession(sessionId, (value) => ({
                ...value,
                currentTurnId: result.currentTurnId,
                executionStatus: result.status,
                streamSequence: 0,
            }));
            if (!result.currentTurnId) return;
            const stream = await post(
                '/api/session/subscribe',
                {
                    sessionId,
                    expectedTurnId: result.currentTurnId,
                    afterTimelineSequence: activeSession?.timelineSequence || 0,
                    afterEventSequence: 0,
                },
                { signal: controller.signal, stream: true }
            );
            await followSessionResponse(
                sessionId,
                message.turnId,
                stream,
                controller.signal,
                result.currentTurnId
            );
        } catch (error) {
            if (error.name !== 'AbortError') {
                appendMessage(sessionId, {
                    id: createId(),
                    role: 'system',
                    content: error.message,
                });
                // 回答提交后，流传输失败不能让已回答卡片重新变为可操作状态。
                if (!committed) updateCard('waiting');
            }
        } finally {
            streams.finish(sessionId, controller);
        }
    };
    const decideApprovals = (message) => {
        const approved = message.decision === 'approve';
        return resolve(
            message,
            '/api/session/approval/decide',
            {
                sessionId: activeSessionId,
                turnId: message.approvalTurnId,
                decisions: message.approvals.map((item) => ({
                    approvalId: item.approvalId,
                    approved,
                })),
            },
            approved ? 'approved' : 'denied'
        );
    };
    const submitAskUser = (message, answers, skip) =>
        resolve(
            message,
            '/api/ask-user/answer',
            { askUserId: message.askUserId, answers, skip },
            'resolved'
        );
    return { decideApprovals, submitAskUser };
}
