import { finishTool, appendToolOutput, finishSubagent } from '../toolHistory.js';
import { taskIdFromOutput, createId, withoutMockLabel } from '../utils/chat.js';

/** 创建按会话和过程记录标识更新活动项的函数，保留已有工具输入与输出。 */
export function createActivityUpdater(updateSession) {
    return (sessionId, assistantMessageId, messageId, defaults, updater) => {
        updateSession(sessionId, (session) => {
            const index = session.messages.findIndex((message) => message.id === messageId);
            if (index < 0) {
                return {
                    ...session,
                    updatedAt: Date.now(),
                    messages: [
                        ...session.messages,
                        updater({
                            id: messageId,
                            role: 'activity',
                            turnId: assistantMessageId,
                            status: 'running',
                            startedAt: Date.now(),
                            ...defaults,
                        }),
                    ],
                };
            }
            return {
                ...session,
                updatedAt: Date.now(),
                messages: session.messages.map((message, messageIndex) =>
                    messageIndex === index ? updater(message) : message
                ),
            };
        });
    };
}

/** 将执行事件投影为消息、工具过程和交互卡片，维护会话级游标与执行身份。 */
export function createTurnEventProcessor({
    upsertActivity,
    updateSession,
    streamSequencesRef,
    turnIdsRef,
    queueTextDelta,
    loadSubtasks,
    appendMessage,
    upsertPresentation,
    loadApprovals,
    flushQueuedText,
    clearQueuedText,
}) {
    const updateSubagentExecution = (sessionId, assistantMessageId, event, updater) => {
        const executionId = event.taskId || event.source || event.agentId || event.id;
        const messageId = `subagent-execution-${executionId}`;
        updateSession(sessionId, (session) => {
            const index = session.messages.findIndex((message) => message.id === messageId);
            const current =
                index < 0
                    ? {
                          id: messageId,
                          role: 'subagent_execution',
                          turnId: assistantMessageId,
                          executionId,
                          source: event.source,
                          taskId: event.taskId,
                          parentSessionId: event.parentSessionId,
                          agentId: event.agentId,
                          depth: event.depth || 1,
                          status: 'running',
                          startedAt: Date.now(),
                          steps: [],
                      }
                    : session.messages[index];
            const next = updater({
                ...current,
                source: event.source || current.source,
                taskId: event.taskId || current.taskId,
                parentSessionId: event.parentSessionId || current.parentSessionId,
                agentId: event.agentId || current.agentId,
                depth: event.depth || current.depth || 1,
            });
            return {
                ...session,
                updatedAt: Date.now(),
                messages:
                    index < 0
                        ? [...session.messages, next]
                        : session.messages.map((message, messageIndex) =>
                              messageIndex === index ? next : message
                          ),
            };
        });
    };

    const updateSubagentStep = (sessionId, assistantMessageId, event, kind, updater) => {
        updateSubagentExecution(sessionId, assistantMessageId, event, (execution) => {
            const stepId = `subagent-step-${event.source || event.agentId}-${kind}-${event.id}`;
            const index = (execution.steps || []).findIndex((step) => step.id === stepId);
            const current =
                index < 0
                    ? {
                          id: stepId,
                          role: 'activity',
                          activityType: kind,
                          title: event.title || (kind === 'tool' ? '调用工具' : '分析与推理'),
                          toolName: event.toolName,
                          status: 'running',
                          startedAt: Date.now(),
                      }
                    : execution.steps[index];
            const next = updater(current);
            return {
                ...execution,
                steps:
                    index < 0
                        ? [...(execution.steps || []), next]
                        : execution.steps.map((step, stepIndex) =>
                              stepIndex === index ? next : step
                          ),
            };
        });
    };

    const applySubagentEvent = (sessionId, assistantMessageId, event) => {
        if (event.type === 'subagent_start') {
            updateSubagentExecution(sessionId, assistantMessageId, event, (execution) => ({
                ...execution,
                status: 'running',
                startedAt: execution.startedAt || Date.now(),
            }));
            return;
        }
        if (event.type === 'subagent_result') {
            updateSubagentExecution(sessionId, assistantMessageId, event, (execution) => ({
                ...execution,
                result: event.text || '',
                status: event.status === 'error' ? 'error' : execution.status,
            }));
            return;
        }
        if (event.type === 'subagent_end') {
            updateSubagentExecution(sessionId, assistantMessageId, event, (execution) => ({
                ...finishSubagent(
                    execution,
                    execution.status === 'error' ? 'error' : event.status || 'ended',
                    true
                ),
                durationMs: event.durationMs,
            }));
            return;
        }
        if (event.type === 'phase_start') {
            updateSubagentStep(sessionId, assistantMessageId, event, 'reasoning', (step) => step);
        }
        if (
            event.type === 'reasoning_delta' ||
            event.type === 'thinking_delta' ||
            event.type === 'text_delta'
        ) {
            updateSubagentStep(sessionId, assistantMessageId, event, 'reasoning', (step) => ({
                ...step,
                content: `${step.content || ''}${event.text || ''}`,
            }));
        }
        if (event.type === 'phase_end') {
            updateSubagentStep(sessionId, assistantMessageId, event, 'reasoning', (step) => ({
                ...step,
                status: event.status || 'success',
                durationMs: event.durationMs,
                details: event.details || step.details,
            }));
        }
        if (event.type === 'tool_start') {
            updateSubagentStep(sessionId, assistantMessageId, event, 'tool', (step) => ({
                ...step,
                input: '',
                output: '',
                outputSnapshot: false,
            }));
        }
        if (event.type === 'tool_input_delta') {
            updateSubagentStep(sessionId, assistantMessageId, event, 'tool', (step) => ({
                ...step,
                input: `${step.input || ''}${event.details || ''}`,
            }));
        }
        if (event.type === 'tool_output_delta') {
            updateSubagentStep(sessionId, assistantMessageId, event, 'tool', (step) =>
                appendToolOutput(step, event.details)
            );
        }
        if (event.type === 'tool_end') {
            updateSubagentStep(sessionId, assistantMessageId, event, 'tool', (step) =>
                finishTool(step, event)
            );
        }
        if (event.type === 'presentation_created') {
            let detail = event.details || '';
            try {
                const parsed = typeof detail === 'string' ? JSON.parse(detail) : detail;
                const block = parsed?.block || parsed;
                detail = block?.data?.title || block?.type || event.title || '结构化结果';
            } catch (_) {
                /* 保留安全的序列化详情 */
            }
            updateSubagentStep(sessionId, assistantMessageId, event, 'artifact', (step) => ({
                ...step,
                title: event.title || '生成结构化结果',
                details: String(detail),
                status: 'success',
            }));
        }
        if (event.type === 'context_compacted' || event.type === 'context_compaction_failed') {
            updateSubagentStep(sessionId, assistantMessageId, event, 'context', (step) => ({
                ...step,
                title: event.title || '上下文压缩',
                details: event.details,
                status: event.type === 'context_compaction_failed' ? 'error' : 'success',
            }));
        }
    };

    const applyTurnEvent = (sessionId, assistantMessageId, event, options = { animate: true }) => {
        const streamSequence = Number(event.streamSequence);
        if (Number.isSafeInteger(streamSequence) && streamSequence > 0) {
            const previous = streamSequencesRef.current.get(sessionId) || 0;
            if (streamSequence <= previous) return false;
            streamSequencesRef.current.set(sessionId, streamSequence);
            updateSession(sessionId, (session) => ({
                ...session,
                streamSequence: Math.max(Number(session.streamSequence) || 0, streamSequence),
            }));
        }
        if (event.type === 'turn_start') {
            turnIdsRef.current.set(sessionId, event.id);
            updateSession(sessionId, (session) => ({
                ...session,
                currentTurnId: event.id,
                currentAssistantMessageId: assistantMessageId,
                executionStatus: 'running',
                updatedAt: Date.now(),
            }));
            upsertActivity(
                sessionId,
                assistantMessageId,
                `turn-${assistantMessageId}`,
                {
                    activityType: 'turn',
                    title: event.title || '开始处理',
                },
                (message) => ({
                    ...message,
                    title: event.title || message.title,
                    status: event.status || 'running',
                })
            );
        }
        if (
            event.type === 'subagent_start' ||
            event.type === 'subagent_result' ||
            event.type === 'subagent_end'
        ) {
            applySubagentEvent(sessionId, assistantMessageId, event);
            return false;
        }
        if (event.source) {
            applySubagentEvent(sessionId, assistantMessageId, event);
            if (
                [
                    'phase_start',
                    'phase_end',
                    'reasoning_delta',
                    'thinking_delta',
                    'text_delta',
                    'tool_start',
                    'tool_input_delta',
                    'tool_output_delta',
                    'tool_end',
                ].includes(event.type)
            ) {
                return false;
            }
        }
        if (['execution_notice', 'context_compaction_failed'].includes(event.type)) {
            upsertActivity(
                sessionId,
                assistantMessageId,
                `notice-${event.source || 'root'}-${event.id}`,
                {
                    activityType: 'notice',
                    title: event.title || '执行异常提示',
                },
                (message) => ({
                    ...message,
                    title: event.title || message.title,
                    content: event.text || '执行中出现异常，正在尝试继续处理。',
                    status: event.status || 'error',
                })
            );
            return false;
        }
        if (event.type === 'text_delta') {
            const delta = event.text || '';
            updateSession(sessionId, (session) => {
                const exists = session.messages.some(
                    (message) => message.id === assistantMessageId
                );
                const messages = exists
                    ? session.messages
                    : [
                          ...session.messages,
                          {
                              id: assistantMessageId,
                              role: 'assistant',
                              turnId: assistantMessageId,
                              content: '',
                              isStreaming: true,
                          },
                      ];
                if (options.animate) return { ...session, updatedAt: Date.now(), messages };
                return {
                    ...session,
                    updatedAt: Date.now(),
                    messages: messages.map((message) =>
                        message.id === assistantMessageId
                            ? { ...message, content: `${message.content || ''}${delta}` }
                            : message
                    ),
                };
            });
            if (options.animate) queueTextDelta(sessionId, assistantMessageId, delta);
            return false;
        }
        if (event.type === 'reasoning_delta' || event.type === 'thinking_delta') {
            upsertActivity(
                sessionId,
                assistantMessageId,
                `reasoning-${event.id}`,
                {
                    activityType: 'reasoning',
                    title: event.title || '思考过程',
                    content: '',
                },
                (message) => ({
                    ...message,
                    title: event.title || message.title,
                    content: `${message.content || ''}${event.text || ''}`,
                    status: 'running',
                })
            );
        }
        if (event.type === 'phase_end') {
            updateSession(sessionId, (session) => ({
                ...session,
                updatedAt: Date.now(),
                messages: session.messages.map((message) =>
                    message.id === `reasoning-${event.id}`
                        ? {
                              ...message,
                              status: event.status || 'success',
                              details: event.details,
                              durationMs: event.durationMs,
                          }
                        : message
                ),
            }));
        }
        if (event.type === 'tool_start') {
            upsertActivity(
                sessionId,
                assistantMessageId,
                `tool-${event.id}`,
                {
                    activityType: 'tool',
                    title: event.title || '调用工具',
                    toolName: event.toolName,
                    input: '',
                    output: '',
                },
                (message) => ({
                    ...message,
                    input: '',
                    output: '',
                    outputSnapshot: false,
                    status: 'running',
                })
            );
        }
        if (event.type === 'tool_input_delta') {
            upsertActivity(
                sessionId,
                assistantMessageId,
                `tool-${event.id}`,
                {
                    activityType: 'tool',
                    title: event.title || '调用工具',
                    toolName: event.toolName,
                },
                (message) => ({ ...message, input: `${message.input || ''}${event.details || ''}` })
            );
        }
        if (event.type === 'tool_output_delta') {
            upsertActivity(
                sessionId,
                assistantMessageId,
                `tool-${event.id}`,
                {
                    activityType: 'tool',
                    title: event.title || '调用工具',
                    toolName: event.toolName,
                },
                (message) => appendToolOutput(message, event.details)
            );
            if (event.toolName === 'agent_spawn' || event.toolName === 'agent_send') {
                const taskId = taskIdFromOutput(event.details);
                if (taskId) {
                    const card = {
                        id: `subtask-${taskId}`,
                        role: 'subtask',
                        turnId: assistantMessageId,
                        taskId,
                        worker: event.toolName === 'agent_spawn' ? 'general_worker' : '子 Agent',
                        status: 'running',
                    };
                    updateSession(sessionId, (session) =>
                        session.messages.some((message) => message.id === card.id)
                            ? session
                            : { ...session, messages: [...session.messages, card] }
                    );
                    loadSubtasks(sessionId);
                }
            }
        }
        if (event.type === 'tool_end') {
            upsertActivity(
                sessionId,
                assistantMessageId,
                `tool-${event.id}`,
                {
                    activityType: 'tool',
                    title: event.title || '调用工具',
                    toolName: event.toolName,
                },
                (message) => finishTool(message, event)
            );
        }
        if (event.type === 'todo_updated') {
            try {
                const tasks =
                    typeof event.details === 'string' ? JSON.parse(event.details) : event.details;
                const card = {
                    id: `todo-${sessionId}`,
                    role: 'todo',
                    turnId: assistantMessageId,
                    tasks: Array.isArray(tasks) ? tasks : [],
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
            } catch (error) {
                appendMessage(sessionId, {
                    id: createId(),
                    role: 'system',
                    content: '任务进度格式无效',
                });
            }
        }
        if (event.type === 'presentation_created') {
            try {
                const details =
                    typeof event.details === 'string' ? JSON.parse(event.details) : event.details;
                const block = details?.block || details;
                upsertPresentation(
                    sessionId,
                    {
                        blockId: block?.blockId || event.id,
                        type: block?.type,
                        schemaVersion: block?.schemaVersion || 1,
                        position: block?.position || 0,
                        data: block?.data || {},
                    },
                    assistantMessageId
                );
            } catch (error) {
                appendMessage(sessionId, {
                    id: createId(),
                    role: 'system',
                    content: '结构化结果格式无效',
                });
            }
        }
        if (event.type === 'approval_required') {
            // MySQL 不保存工具输入片段。审批记录已包含准确冻结的输入，
            // 可在刷新后恢复待处理工具卡片。
            try {
                const requests =
                    typeof event.details === 'string' ? JSON.parse(event.details) : event.details;
                (Array.isArray(requests) ? requests : []).forEach((request) => {
                    if (event.source) {
                        updateSubagentStep(
                            sessionId,
                            assistantMessageId,
                            { ...event, id: request.toolCallId, toolName: request.toolName },
                            'tool',
                            (step) => ({
                                ...step,
                                input: JSON.stringify(request.input || {}),
                                status: 'running',
                            })
                        );
                        return;
                    }
                    upsertActivity(
                        sessionId,
                        assistantMessageId,
                        `tool-${request.toolCallId}`,
                        {
                            activityType: 'tool',
                            title: '调用工具',
                            toolName: request.toolName,
                        },
                        (message) => ({
                            ...message,
                            input: JSON.stringify(request.input || {}),
                            status: 'running',
                        })
                    );
                });
            } catch (_) {
                appendMessage(sessionId, {
                    id: createId(),
                    role: 'system',
                    content: '审批内容展示出现异常，正在尝试重新读取审批信息。',
                });
            }
            const turnId = turnIdsRef.current.get(sessionId);
            if (turnId) {
                updateSession(sessionId, (session) => ({
                    ...session,
                    executionStatus: 'waiting_approval',
                    updatedAt: Date.now(),
                }));
                loadApprovals(sessionId, turnId, assistantMessageId);
            }
        }
        if (event.type === 'ask_user_required') {
            try {
                const raw =
                    typeof event.details === 'string' ? JSON.parse(event.details) : event.details;
                const questions =
                    typeof raw.questionsJson === 'string'
                        ? JSON.parse(raw.questionsJson)
                        : raw.questions;
                const askUserId = raw.askUserId || event.id;
                const card = {
                    id: `ask-${askUserId}`,
                    role: 'ask_user',
                    turnId: assistantMessageId,
                    askUserId,
                    questions,
                    status: 'waiting',
                };
                updateSession(sessionId, (session) => {
                    const exists = session.messages.some((message) => message.id === card.id);
                    return {
                        ...session,
                        executionStatus: 'waiting_ask_user',
                        updatedAt: Date.now(),
                        messages: exists
                            ? session.messages.map((message) =>
                                  message.id === card.id ? { ...message, ...card } : message
                              )
                            : [...session.messages, card],
                    };
                });
            } catch (error) {
                appendMessage(sessionId, {
                    id: createId(),
                    role: 'system',
                    content: '问题单格式无效',
                });
            }
        }
        if (event.type === 'ask_user_resolved') {
            const askUserId = event.details?.askUserId || event.id;
            updateSession(sessionId, (session) => ({
                ...session,
                messages: session.messages.map((message) =>
                    message.role === 'ask_user' && message.askUserId === askUserId
                        ? { ...message, status: 'resolved' }
                        : message
                ),
            }));
        }
        if (event.type === 'error' || event.type === 'cancelled') {
            flushQueuedText(sessionId, assistantMessageId);
            const cancelled = event.type === 'cancelled';
            updateSession(sessionId, (session) => ({
                ...session,
                executionStatus: cancelled ? 'cancelled' : 'error',
                updatedAt: Date.now(),
                messages: session.messages.map((message) =>
                    message.turnId === assistantMessageId && message.role === 'subagent_execution'
                        ? finishSubagent(message, cancelled ? 'cancelled' : 'error')
                        : message.turnId === assistantMessageId && message.status === 'running'
                          ? { ...message, status: cancelled ? 'cancelled' : 'error' }
                          : message
                ),
            }));
            upsertActivity(
                sessionId,
                assistantMessageId,
                `error-${assistantMessageId}`,
                {
                    activityType: 'error',
                    title: event.title || (cancelled ? '已停止' : '运行失败'),
                    content: event.text || (cancelled ? '本轮执行已停止' : 'Agent 流式调用失败'),
                },
                (message) => ({
                    ...message,
                    status: cancelled ? 'cancelled' : 'error',
                    title: event.title || message.title,
                    content: event.text || message.content,
                    durationMs: event.durationMs,
                })
            );
            return true;
        }
        if (event.type === 'done') {
            clearQueuedText(sessionId, assistantMessageId);
            updateSession(sessionId, (session) => {
                const finalText = event.text || '';
                const comparableFinal = withoutMockLabel(finalText);
                const updated = session.messages
                    .filter((message) => message.id !== assistantMessageId)
                    .map((message) =>
                        message.turnId === assistantMessageId &&
                        message.role === 'subagent_execution'
                            ? finishSubagent(message, 'ended')
                            : message.turnId === assistantMessageId && message.status === 'running'
                              ? {
                                    ...message,
                                    status: 'success',
                                    durationMs:
                                        message.activityType === 'turn'
                                            ? event.durationMs
                                            : message.durationMs,
                                }
                              : message
                    )
                    .filter(
                        (message) =>
                            !(
                                message.turnId === assistantMessageId &&
                                message.activityType === 'reasoning' &&
                                withoutMockLabel(message.content) === comparableFinal
                            )
                    );
                return {
                    ...session,
                    executionStatus: 'completed',
                    updatedAt: Date.now(),
                    messages: [
                        ...updated,
                        {
                            id: assistantMessageId,
                            role: 'assistant',
                            turnId: assistantMessageId,
                            content: finalText,
                            isStreaming: false,
                            latencyMs: event.latencyMs,
                        },
                    ],
                };
            });
            return true;
        }
        return false;
    };

    const showConnectionNotice = (sessionId, assistantMessageId, state) => {
        const restored = state === 'restored';
        const unknown = state === 'unknown';
        upsertActivity(
            sessionId,
            assistantMessageId,
            `connection-${assistantMessageId}`,
            {
                activityType: 'notice',
            },
            (message) => ({
                ...message,
                title: restored
                    ? '连接已恢复'
                    : unknown
                      ? '执行状态尚未确认'
                      : '连接中断，正在恢复',
                content: restored
                    ? '已恢复执行状态。'
                    : unknown
                      ? '当前执行状态尚未确认，请重新加载会话查看结果。'
                      : '正在恢复连接，当前任务状态尚未确认。',
                status: restored ? 'success' : unknown ? 'unknown' : 'running',
            })
        );
        if (unknown) {
            flushQueuedText(sessionId, assistantMessageId);
            updateSession(sessionId, (session) => ({
                ...session,
                executionStatus: 'unknown',
                messages: session.messages.map((message) =>
                    message.id === assistantMessageId ? { ...message, isStreaming: false } : message
                ),
            }));
        }
    };

    return {
        upsertActivity,
        updateSubagentExecution,
        updateSubagentStep,
        applySubagentEvent,
        applyTurnEvent,
        showConnectionNotice,
    };
}
