import { request as apiRequest } from '../api/client.js';
import { createId } from '../utils/chat.js';
/** 查询当前会话的子任务和审批状态，并将结果合并到对应会话；失败说明写入原会话。 */
export function useAgentQueries({ updateSession, sessionsRef, upsertActivity, appendMessage }) {
    const loadSubtasks = async (sessionId) => {
        try {
            const response = await apiRequest(
                `/api/session/subtasks?sessionId=${encodeURIComponent(sessionId)}`
            );
            if (!response.ok) throw new Error('Subtask query failed');
            const tasks = await response.json();
            updateSession(sessionId, (session) => ({
                ...session,
                messages: session.messages.map((message) =>
                    message.id === `subtask-query-notice-${sessionId}`
                        ? {
                              ...message,
                              title: '子任务状态查询已恢复',
                              content: '已恢复子任务状态。',
                              status: 'success',
                          }
                        : message
                ),
            }));
            updateSession(sessionId, (session) => ({
                ...session,
                messages: session.messages.map((message) => {
                    if (message.role !== 'subtask') return message;
                    const task = tasks.find((value) => value.taskId === message.taskId);
                    return task ? { ...message, ...task } : message;
                }),
            }));
        } catch (_) {
            const session = sessionsRef.current.find((item) => item.id === sessionId);
            upsertActivity(
                sessionId,
                session?.currentAssistantMessageId || `subtasks-${sessionId}`,
                `subtask-query-notice-${sessionId}`,
                { activityType: 'notice' },
                (message) => ({
                    ...message,
                    title: '子任务状态查询异常',
                    content: '暂时无法读取子任务状态，正在尝试恢复。',
                    status: 'unknown',
                })
            );
        }
    };

    const loadApprovals = async (sessionId, turnId, assistantMessageId) => {
        try {
            const response = await apiRequest('/api/session/approvals/query', {
                method: 'POST',
                body: JSON.stringify({ sessionId, turnId }),
            });
            if (!response.ok) {
                throw new Error('无法读取待审批操作');
            }
            const payload = await response.json();
            if (payload.approvals?.length) {
                const card = {
                    id: `approval-${turnId}`,
                    role: 'approval',
                    turnId: assistantMessageId,
                    approvalTurnId: turnId,
                    approvals: payload.approvals,
                    status: 'waiting',
                };
                updateSession(sessionId, (session) => {
                    const exists = session.messages.some((message) => message.id === card.id);
                    return {
                        ...session,
                        updatedAt: Date.now(),
                        messages: exists
                            ? session.messages.map((message) =>
                                  message.id === card.id ? { ...message, ...card } : message
                              )
                            : [...session.messages, card],
                    };
                });
            }
        } catch (error) {
            appendMessage(sessionId, {
                id: createId(),
                role: 'system',
                content: error.message || '读取待审批操作失败',
            });
        }
    };

    const cancelSubtask = async (sessionId, taskId) => {
        try {
            const response = await apiRequest('/api/session/subtasks/cancel', {
                method: 'POST',
                body: JSON.stringify({ sessionId, taskId }),
            });
            if (!response.ok) throw new Error('取消子任务失败');
            await loadSubtasks(sessionId);
        } catch (error) {
            appendMessage(sessionId, {
                id: createId(),
                role: 'system',
                content: '取消子任务出现异常，尚未确认子任务已停止。',
            });
        }
    };
    return { loadSubtasks, loadApprovals, cancelSubtask };
}
