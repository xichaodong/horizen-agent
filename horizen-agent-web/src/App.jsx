import { request as apiRequest } from './api/client.js';
import { useSessionCatalog } from './hooks/useSessionCatalog.js';
import { useChatActions } from './hooks/useChatActions.js';
import { useArtifactActions } from './hooks/useArtifactActions.js';
import { useInteractionActions } from './hooks/useInteractionActions.js';
import { useCopyAction } from './hooks/useCopyAction.js';
import { useTurnStream } from './hooks/useTurnStream.js';
import { createTurnEventProcessor, createActivityUpdater } from './stream/turnEvents.js';
import { useAgentQueries } from './hooks/useAgentQueries.js';
import { useConversationHistory } from './hooks/useConversationHistory.js';
import { useTurnObservation } from './hooks/useTurnObservation.js';
import { useTypewriter } from './hooks/useTypewriter.js';
import {
    PREFIX,
    SESSION_PAGE_SIZE,
    MAX_SESSION_TITLE_LENGTH,
    WELCOME_AVATAR_URL,
    SUGGESTIONS,
    taskIdFromOutput,
    listPresentations,
    listMessages,
    createId,
    createSession,
    sessionFromServer,
    groupSessionsByTime,
    copyText,
    statusLabel,
    formatDuration,
    withoutMockLabel,
} from './utils/chat.js';
import ActivityItem from './components/ActivityItem.jsx';
import AskUserCard from './components/AskUserCard.jsx';
import TodoCard from './components/TodoCard.jsx';
import SubagentExecutionCard from './components/SubagentExecutionCard.jsx';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { finishTool, appendToolOutput, finishSubagent } from './toolHistory.js';
import { followTurnStream, executionFailureEvent } from './turnRecovery.js';
import {
    CheckCircleFilled,
    CloseCircleFilled,
    CopyOutlined,
    DeleteOutlined,
    DislikeOutlined,
    DownOutlined,
    EditOutlined,
    ExperimentOutlined,
    LikeOutlined,
    LoadingOutlined,
    PaperClipOutlined,
    PushpinFilled,
    PushpinOutlined,
    ReloadOutlined,
    ToolOutlined,
} from '@ant-design/icons';
import MessageContent from './MessageContent';
import ApprovalCard from './ApprovalCard';
import PresentationCard from './PresentationCard';
import assistantIcon from './assets/horizen-mark.svg';
import collapseIcon from './assets/sidebar-collapse-button.svg';
import newSessionIcon from './assets/new-session-icon.svg';
import moreIcon from './assets/more-operation-button.svg';
import sendActiveIcon from './assets/send-message-button-active.svg';
import sendInactiveIcon from './assets/send-message-button-inactive.svg';
import stopIcon from './assets/stop-generating-button.svg';

const App = () => {
    const [sidebarCollapsed, setSidebarCollapsed] = useState(
        () => window.matchMedia('(max-width: 680px)').matches
    );
    const [inputValue, setInputValue] = useState('');
    const [status, setStatus] = useState({
        loading: true,
        ready: false,
        message: '正在连接 Agent API',
    });

    const turnIdsRef = useRef(new Map());
    const messagesRef = useRef(null);
    const fileInputRef = useRef(null);

    const streamSequencesRef = useRef(new Map());

    const inputRef = useRef(null);
    const {
        sessions,
        setSessions,
        activeSessionId,
        setActiveSessionId,
        historyStatus,
        sessionHasMore,
        loadingMoreSessions,
        openSessionMenuId,
        sessionMenuPosition,
        renameSessionId,
        renameValue,
        setRenameValue,
        loadMoreSentinelRef,
        sessionsRef,
        updateSession,
        handleLoadMoreSessions,
        appendMessage,
        handleNewSession,
        handleDeleteSession,
        openSessionMenu,
        handleRenameStart,
        saveRename,
        handleTogglePin,
        setObserving,
    } = useSessionCatalog({ status, inputRef, setInputValue });

    const activeSession = useMemo(
        () => sessions.find((session) => session.id === activeSessionId) || sessions[0],
        [activeSessionId, sessions]
    );
    const streams = useTurnStream(setObserving);
    const running = Boolean(activeSession?.observing);
    const messages = useMemo(() => activeSession?.messages || [], [activeSession?.messages]);
    const isEmptyConversation = messages.length === 0;
    const hasStreamingReply = messages.some(
        (message) => message.isStreaming || message.status === 'running'
    );
    const sortedCatalogSessions = useMemo(
        () =>
            sessions
                .filter((session) => session.persisted)
                .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt),
        [sessions]
    );
    const sessionGroups = useMemo(
        () => groupSessionsByTime(sortedCatalogSessions),
        [sortedCatalogSessions]
    );

    useEffect(() => {
        apiRequest('/api/status')
            .then((response) => response.json())
            .then((data) => setStatus({ ...data, loading: false }))
            .catch(() =>
                setStatus({
                    loading: false,
                    ready: false,
                    message: 'Agent API 未启动，请先运行后端服务',
                })
            );
    }, []);

    useEffect(() => {
        if (messagesRef.current) {
            messagesRef.current.scrollTop = messagesRef.current.scrollHeight;
        }
    }, [messages, running]);

    const { queueTextDelta, clearQueuedText, flushQueuedText } = useTypewriter(
        setSessions,
        sessionsRef,
        updateSession
    );

    const upsertPresentation = (sessionId, presentation, turnId) => {
        if (!presentation?.blockId) return;
        const card = {
            id: `presentation-${presentation.blockId}`,
            role: 'presentation',
            turnId,
            presentation,
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
    };

    const upsertActivity = createActivityUpdater(updateSession);
    const { loadSubtasks, loadApprovals, cancelSubtask } = useAgentQueries({
        updateSession,
        sessionsRef,
        upsertActivity,
        appendMessage,
    });
    const { applyTurnEvent, showConnectionNotice } = createTurnEventProcessor({
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
    });
    useConversationHistory({
        status,
        activeSessionId,
        sessions,
        updateSession,
        appendMessage,
        streamSequencesRef,
        applyTurnEvent,
    });

    const recoveryJson = async (url, sessionId, signal) => {
        const response = await apiRequest(url, {
            method: 'POST',
            body: JSON.stringify({ sessionId }),
            signal,
        });
        if (!response.ok) throw new Error('执行状态恢复失败');
        return response.json();
    };

    const followSessionResponse = (
        sessionId,
        assistantMessageId,
        response,
        signal,
        expectedTurnId,
        previousTurnId
    ) =>
        followTurnStream({
            response,
            signal,
            expectedTurnId,
            previousTurnId,
            onEvent: (event) => applyTurnEvent(sessionId, assistantMessageId, event),
            onConnection: (state) => showConnectionNotice(sessionId, assistantMessageId, state),
            queryExecution: () => recoveryJson('/api/session/query', sessionId, signal),
            loadHistory: () => recoveryJson('/api/session/messages/query', sessionId, signal),
            subscribe: (turnId) =>
                apiRequest('/api/session/subscribe', {
                    method: 'POST',
                    headers: { Accept: 'text/event-stream' },
                    body: JSON.stringify({
                        sessionId,
                        expectedTurnId: turnId,
                        afterEventSequence: streamSequencesRef.current.get(sessionId) || 0,
                    }),
                    signal,
                }),
        });

    const artifacts = useArtifactActions({ activeSessionId, appendMessage, running });
    const { attachments, setAttachments, uploading, handleUpload, openArtifact } = artifacts;
    const { copiedId, handleCopy } = useCopyAction();
    const execution = {
        recoveryJson,
        applyTurnEvent,
        executionFailureEvent,
        streams,
        streamSequencesRef,
        turnIdsRef,
        followSessionResponse,
    };
    const sessionActions = {
        activeSessionId,
        activeSession,
        running,
        messages,
        updateSession,
        appendMessage,
        upsertActivity,
    };
    const { handleStop, sendMessage, handleKeyDown, retryLastMessage } = useChatActions({
        composer: { inputValue, setInputValue, inputRef },
        session: sessionActions,
        execution,
        artifacts,
    });
    const { decideApprovals, submitAskUser } = useInteractionActions({
        session: sessionActions,
        execution,
    });
    useTurnObservation({
        messages,
        activeSessionId,
        loadSubtasks,
        status,
        sessions,
        sessionsRef,
        activeSession,
        updateSession,
        applyTurnEvent,
        followSessionResponse,
        streamSequencesRef,
        turnIdsRef,
        streams,
        showConnectionNotice,
    });

    const composerNode = (
        <div className={`${PREFIX}__composer ${running ? 'is-running' : ''}`}>
            <textarea
                ref={inputRef}
                autoFocus
                value={inputValue}
                rows={2}
                maxLength={1500}
                disabled={running}
                aria-label="消息"
                placeholder="描述任务，或输入一个问题…"
                onChange={(event) => setInputValue(event.target.value)}
                onKeyDown={handleKeyDown}
            />
            {attachments.length ? (
                <div className={`${PREFIX}__attachment-list`}>
                    {attachments.map((artifact) => (
                        <span className={`${PREFIX}__attachment`} key={artifact.artifactId}>
                            {artifact.title}
                            <button
                                type="button"
                                onClick={() =>
                                    setAttachments((current) =>
                                        current.filter(
                                            (item) => item.artifactId !== artifact.artifactId
                                        )
                                    )
                                }
                            >
                                ×
                            </button>
                        </span>
                    ))}
                </div>
            ) : null}
            <div className={`${PREFIX}__composer-actions`}>
                <div className={`${PREFIX}__model-hint`}>
                    {status.modelName || 'AgentScope HarnessAgent'}
                    {status.gateway?.mode === 'mock' ? ' · Mock 演示数据' : ''}
                </div>
                <input ref={fileInputRef} type="file" hidden onChange={handleUpload} />
                <button
                    type="button"
                    className={`${PREFIX}__upload-button`}
                    disabled={running || uploading}
                    onClick={() => fileInputRef.current?.click()}
                    title="上传文件"
                >
                    {uploading ? <LoadingOutlined /> : <PaperClipOutlined />}
                    <span>上传</span>
                </button>
                {running ? (
                    <button
                        type="button"
                        className={`${PREFIX}__stop-button`}
                        onClick={handleStop}
                        aria-label="停止执行"
                        title="停止执行"
                    >
                        <img src={stopIcon} alt="" />
                    </button>
                ) : (
                    <button
                        type="button"
                        className={`${PREFIX}__send-button`}
                        disabled={!inputValue.trim()}
                        onClick={() => sendMessage()}
                        aria-label="发送"
                        title="发送"
                    >
                        <img src={inputValue.trim() ? sendActiveIcon : sendInactiveIcon} alt="" />
                    </button>
                )}
            </div>
        </div>
    );

    const menuSession = sessions.find((session) => session.id === openSessionMenuId);

    return (
        <div className={`${PREFIX} ${sidebarCollapsed ? `${PREFIX}--sidebar-collapsed` : ''}`}>
            {!status.loading && !status.ready ? (
                <div className={`${PREFIX}__offline`} role="status">
                    {status.message}
                </div>
            ) : null}
            <aside className={`${PREFIX}__sidebar`}>
                <div className={`${PREFIX}__brand-row`}>
                    <div className={`${PREFIX}__brand`}>
                        <img className={`${PREFIX}__brand-mark`} src={assistantIcon} alt="" />
                        <strong>Horizen Agent</strong>
                    </div>
                    <div className={`${PREFIX}__brand-actions`}>
                        <button
                            type="button"
                            className={`${PREFIX}__collapse`}
                            onClick={() => setSidebarCollapsed((value) => !value)}
                            aria-label={sidebarCollapsed ? '展开会话栏' : '收起会话栏'}
                        >
                            <img src={collapseIcon} alt="" />
                        </button>
                        {sidebarCollapsed ? (
                            <button
                                type="button"
                                className={`${PREFIX}__compact-new-session`}
                                onClick={handleNewSession}
                                aria-label="新对话"
                            >
                                <img src={newSessionIcon} alt="" />
                            </button>
                        ) : null}
                    </div>
                </div>
                {!sidebarCollapsed ? (
                    <button
                        type="button"
                        className={`${PREFIX}__new-session`}
                        onClick={handleNewSession}
                    >
                        <img src={newSessionIcon} alt="" />
                        <span>新对话</span>
                    </button>
                ) : null}
                <div className={`${PREFIX}__session-list`}>
                    {sessionGroups.length ? (
                        sessionGroups.map((group) => (
                            <section className={`${PREFIX}__session-group`} key={group.key}>
                                <h3 className={`${PREFIX}__session-group-title`}>{group.label}</h3>
                                {group.sessions.map((session) => (
                                    <div
                                        className={`${PREFIX}__session ${session.id === activeSessionId ? 'is-active' : ''}
                                    ${openSessionMenuId === session.id ? 'is-menu-open' : ''}`}
                                        key={session.id}
                                    >
                                        {renameSessionId === session.id ? (
                                            <input
                                                className={`${PREFIX}__session-rename-input`}
                                                autoFocus
                                                value={renameValue}
                                                maxLength={MAX_SESSION_TITLE_LENGTH}
                                                onChange={(event) =>
                                                    setRenameValue(event.target.value)
                                                }
                                                onBlur={() => saveRename(session)}
                                                onKeyDown={(event) => {
                                                    if (event.key === 'Enter')
                                                        event.currentTarget.blur();
                                                }}
                                            />
                                        ) : (
                                            <>
                                                <button
                                                    type="button"
                                                    className={`${PREFIX}__session-main`}
                                                    onClick={() => setActiveSessionId(session.id)}
                                                >
                                                    <span className={`${PREFIX}__session-title`}>
                                                        <strong>{session.title}</strong>
                                                    </span>
                                                    {running && session.id === activeSessionId ? (
                                                        <span
                                                            className={`${PREFIX}__session-spinner`}
                                                        >
                                                            <LoadingOutlined />
                                                        </span>
                                                    ) : null}
                                                </button>
                                                <button
                                                    type="button"
                                                    className={`${PREFIX}__session-more`}
                                                    onMouseDown={(event) => event.stopPropagation()}
                                                    onPointerDown={(event) => {
                                                        event.preventDefault();
                                                        openSessionMenu(event, session.id);
                                                    }}
                                                    onClick={(event) =>
                                                        openSessionMenu(event, session.id)
                                                    }
                                                    onKeyDown={(event) => {
                                                        if (
                                                            event.key === 'Enter' ||
                                                            event.key === ' '
                                                        ) {
                                                            event.preventDefault();
                                                            openSessionMenu(event, session.id);
                                                        }
                                                    }}
                                                    aria-label="会话操作"
                                                    aria-haspopup="menu"
                                                    aria-expanded={openSessionMenuId === session.id}
                                                >
                                                    <img src={moreIcon} alt="" />
                                                </button>
                                            </>
                                        )}
                                    </div>
                                ))}
                            </section>
                        ))
                    ) : (
                        <div className={`${PREFIX}__empty-history`}>
                            {!status.ready
                                ? '连接后显示会话历史'
                                : historyStatus === 'loading'
                                  ? '加载中...'
                                  : historyStatus === 'failed'
                                    ? '历史会话暂时加载失败'
                                    : '还没有历史会话'}
                        </div>
                    )}
                    {sessionHasMore ? (
                        <div
                            ref={loadMoreSentinelRef}
                            className={`${PREFIX}__load-more`}
                            onClick={handleLoadMoreSessions}
                            role="button"
                            tabIndex={0}
                        >
                            {loadingMoreSessions ? '加载中...' : '滚动加载更多'}
                        </div>
                    ) : null}
                </div>
            </aside>
            {menuSession ? (
                <div
                    className={`${PREFIX}__session-menu`}
                    style={sessionMenuPosition}
                    role="menu"
                    onMouseDown={(event) => event.stopPropagation()}
                >
                    <button
                        type="button"
                        role="menuitem"
                        onClick={() => handleTogglePin(menuSession)}
                    >
                        {menuSession.pinned ? <PushpinFilled /> : <PushpinOutlined />}
                        <span>{menuSession.pinned ? '取消置顶' : '置顶会话'}</span>
                    </button>
                    <button
                        type="button"
                        role="menuitem"
                        onClick={() => handleRenameStart(menuSession)}
                    >
                        <EditOutlined />
                        <span>重命名</span>
                    </button>
                    <button
                        type="button"
                        role="menuitem"
                        className="is-danger"
                        onClick={() => handleDeleteSession(menuSession.id)}
                    >
                        <DeleteOutlined />
                        <span>删除</span>
                    </button>
                </div>
            ) : null}
            <main className={`${PREFIX}__workspace`}>
                <header className={`${PREFIX}__workspace-head`}>
                    <div>
                        <span className={`${PREFIX}__workspace-kicker`}>HORIZEN / WORKSPACE</span>
                        <h1>
                            {isEmptyConversation
                                ? '会话工作台'
                                : activeSession?.title || '会话工作台'}
                        </h1>
                    </div>
                    <span
                        className={`${PREFIX}__connection ${status.ready ? 'is-ready' : ''}`}
                        role="status"
                    >
                        <i aria-hidden="true" />
                        {status.loading ? '连接中' : status.ready ? '已连接' : '未连接'}
                    </span>
                </header>
                <section
                    className={`${PREFIX}__conversation ${isEmptyConversation ? 'is-empty' : ''}`}
                    ref={messagesRef}
                >
                    {isEmptyConversation ? (
                        <div className={`${PREFIX}__empty-state`}>
                            <div className={`${PREFIX}__welcome`}>
                                <img
                                    className={`${PREFIX}__welcome-mark`}
                                    src={WELCOME_AVATAR_URL}
                                    alt="Horizen Agent"
                                />
                                <div>
                                    <p className={`${PREFIX}__welcome-eyebrow`}>从一个任务开始</p>
                                    <h2>让想法，走到结果。</h2>
                                    <p className={`${PREFIX}__welcome-copy`}>
                                        在这里提问、查看执行过程，并继续你的会话。
                                    </p>
                                </div>
                            </div>
                            {status.modelName === 'scripted-web' ? (
                                <p className={`${PREFIX}__demo-note`}>
                                    当前为离线演示：回复由脚本生成，可以体验发送、取消和会话切换。
                                </p>
                            ) : null}
                            <div className={`${PREFIX}__empty-composer`}>{composerNode}</div>
                            <div className={`${PREFIX}__suggestions`}>
                                {SUGGESTIONS.map((suggestion) => (
                                    <button
                                        type="button"
                                        key={suggestion}
                                        onClick={() => sendMessage(suggestion)}
                                    >
                                        {suggestion}
                                    </button>
                                ))}
                            </div>
                        </div>
                    ) : (
                        <div className={`${PREFIX}__message-list`}>
                            {messages.map((message) =>
                                message.role === 'system' ? (
                                    <div
                                        className={`${PREFIX}__notice`}
                                        role="status"
                                        key={message.id}
                                    >
                                        {message.content}
                                    </div>
                                ) : message.role === 'activity' ? (
                                    <ActivityItem
                                        message={message}
                                        key={message.id}
                                        onRetry={
                                            message.activityType === 'error' && !running
                                                ? retryLastMessage
                                                : undefined
                                        }
                                    />
                                ) : message.role === 'presentation' ? (
                                    <PresentationCard
                                        presentation={message.presentation}
                                        onOpenArtifact={openArtifact}
                                        key={message.id}
                                    />
                                ) : message.role === 'approval' ? (
                                    <ApprovalCard
                                        message={message}
                                        onDecide={decideApprovals}
                                        key={message.id}
                                    />
                                ) : message.role === 'todo' ? (
                                    <TodoCard message={message} key={message.id} />
                                ) : message.role === 'subagent_execution' ? (
                                    <SubagentExecutionCard message={message} key={message.id} />
                                ) : message.role === 'subtask' ? (
                                    <article className={`${PREFIX}__todo-block`} key={message.id}>
                                        <div className={`${PREFIX}__todo-head`}>
                                            <span>子任务：{message.worker}</span>
                                            <span>
                                                {message.status === 'running'
                                                    ? '执行中'
                                                    : message.status}
                                            </span>
                                        </div>
                                        <div className={`${PREFIX}__text-small`}>
                                            任务 ID：{message.taskId}
                                        </div>
                                        {message.result && (
                                            <div className={`${PREFIX}__text-small`}>
                                                {message.result}
                                            </div>
                                        )}
                                        {message.error && (
                                            <div className={`${PREFIX}__text-small`}>
                                                {message.error}
                                            </div>
                                        )}
                                        {message.status === 'running' && (
                                            <button
                                                type="button"
                                                onClick={() =>
                                                    cancelSubtask(activeSessionId, message.taskId)
                                                }
                                            >
                                                取消任务
                                            </button>
                                        )}
                                    </article>
                                ) : message.role === 'ask_user' ? (
                                    <AskUserCard
                                        message={message}
                                        onSubmit={submitAskUser}
                                        key={message.id}
                                    />
                                ) : (
                                    <article
                                        className={`${PREFIX}__message ${PREFIX}__message--${message.role}`}
                                        key={message.id}
                                    >
                                        <div className={`${PREFIX}__message-body`}>
                                            {message.role === 'assistant' ? (
                                                <div className={`${PREFIX}__answer-label`}>
                                                    <ExperimentOutlined />
                                                    <span>Agent 回复</span>
                                                </div>
                                            ) : null}
                                            <div className={`${PREFIX}__bubble`}>
                                                <MessageContent content={message.content} />
                                            </div>
                                            {message.role === 'user' &&
                                            message.attachments?.length ? (
                                                <div className={`${PREFIX}__attachment-list`}>
                                                    {message.attachments.map((artifact) => (
                                                        <span
                                                            className={`${PREFIX}__attachment`}
                                                            key={artifact.artifactId}
                                                        >
                                                            {artifact.title}
                                                        </span>
                                                    ))}
                                                </div>
                                            ) : null}
                                            {message.role === 'user' ? (
                                                <button
                                                    type="button"
                                                    className={`${PREFIX}__user-copy`}
                                                    onClick={() => handleCopy(message)}
                                                    aria-label="复制"
                                                >
                                                    <CopyOutlined />{' '}
                                                    {copiedId === message.id ? '已复制' : ''}
                                                </button>
                                            ) : message.isStreaming ? null : (
                                                <div className={`${PREFIX}__message-actions`}>
                                                    <button
                                                        type="button"
                                                        onClick={() => handleCopy(message)}
                                                        title="复制"
                                                    >
                                                        <CopyOutlined />
                                                    </button>
                                                    <button
                                                        type="button"
                                                        onClick={retryLastMessage}
                                                        title="重新生成"
                                                    >
                                                        <ReloadOutlined />
                                                    </button>
                                                    {message.latencyMs ? (
                                                        <span className={`${PREFIX}__latency`}>
                                                            总计 {formatDuration(message.latencyMs)}
                                                        </span>
                                                    ) : null}
                                                </div>
                                            )}
                                        </div>
                                    </article>
                                )
                            )}
                            {running && !hasStreamingReply ? (
                                <div className={`${PREFIX}__activity`} role="status">
                                    <div className={`${PREFIX}__activity-header is-static`}>
                                        <LoadingOutlined
                                            className={`${PREFIX}__activity-mark is-spinning`}
                                        />
                                        <span className={`${PREFIX}__activity-state`}>
                                            正在思考并生成回复...
                                        </span>
                                    </div>
                                </div>
                            ) : null}
                        </div>
                    )}
                </section>
                {isEmptyConversation ? null : (
                    <footer className={`${PREFIX}__composer-wrap`}>
                        {composerNode}
                        <p className={`${PREFIX}__composer-disclaimer`}>
                            AI 助手可能会产生不准确的信息，请核实重要数据
                        </p>
                    </footer>
                )}
            </main>
        </div>
    );
};

export default App;
