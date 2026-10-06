import {request as apiRequest} from '../api/client.js';
import {useState, useRef, useEffect, useReducer} from 'react';
import {useLatest} from './useLatest.js';
import {sessionsReducer} from '../state/sessions.js';
import {
    createSession,
    SESSION_PAGE_SIZE,
    sessionFromServer,
    createId,
    PREFIX,
    MAX_SESSION_TITLE_LENGTH,
} from '../utils/chat.js';

/** 维护会话目录、分页、重命名与置顶，异步结果只合并到其对应的会话。 */
export function useSessionCatalog({status, setInputValue, inputRef}) {
    const [sessions, dispatch] = useReducer(sessionsReducer, undefined, () => [createSession()]);
    const setSessions = (sessions) => dispatch({type: 'catalog/replace', sessions});
    const setObserving = (sessionId, value) =>
        dispatch({type: 'session/observing', sessionId, value});

    const [activeSessionId, setActiveSessionId] = useState(() => sessions[0].id);

    const [historyStatus, setHistoryStatus] = useState('loading');

    const [sessionHasMore, setSessionHasMore] = useState(false);

    const [sessionCursor, setSessionCursor] = useState(null);

    const [loadingMoreSessions, setLoadingMoreSessions] = useState(false);

    const [openSessionMenuId, setOpenSessionMenuId] = useState(null);

    const [sessionMenuPosition, setSessionMenuPosition] = useState({top: 0, left: 0});

    const [renameSessionId, setRenameSessionId] = useState(null);

    const [renameValue, setRenameValue] = useState('');

    const loadMoreSentinelRef = useRef(null);

    const loadingMoreRef = useRef(false);

    const sessionsRef = useRef(sessions);

    useEffect(() => {
        sessionsRef.current = sessions;
    }, [sessions]);

    useEffect(() => {
        if (!status.ready) return undefined;
        const controller = new AbortController();
        setHistoryStatus('loading');
        apiRequest('/api/sessions/query', {
            method: 'POST',
            body: JSON.stringify({limit: SESSION_PAGE_SIZE}),
            signal: controller.signal,
        })
            .then((response) => {
                if (!response.ok) throw new Error('会话列表加载失败');
                return response.json();
            })
            .then((payload) => {
                const remote = (payload.sessions || []).map(sessionFromServer);
                setSessions((current) => {
                    const drafts = current.filter((session) => !session.persisted);
                    const liveById = new Map(
                        current
                            .filter((session) => session.persisted)
                            .map((session) => [session.id, session])
                    );
                    const restored = remote.map((session) => {
                        const live = liveById.get(session.id);
                        return live
                            ? {
                                ...session,
                                messages: live.messages,
                                historyLoaded: live.historyLoaded,
                                currentAssistantMessageId: live.currentAssistantMessageId,
                            }
                            : session;
                    });
                    return [...drafts, ...restored];
                });
                setSessionHasMore(Boolean(payload.hasMore));
                setSessionCursor(payload.nextCursor || null);
                setHistoryStatus('ready');
            })
            .catch((error) => {
                if (error.name !== 'AbortError') {
                    setHistoryStatus('failed');
                    console.warn('会话列表加载失败', error);
                }
            });
        return () => controller.abort();
    }, [status.ready]);

    const updateSession = (sessionId, update) =>
        dispatch({type: 'session/update', sessionId, update});

    const handleLoadMoreSessions = async () => {
        if (!sessionHasMore || !sessionCursor || loadingMoreRef.current) return;
        loadingMoreRef.current = true;
        setLoadingMoreSessions(true);
        try {
            const response = await apiRequest('/api/sessions/query', {
                method: 'POST',
                body: JSON.stringify({limit: SESSION_PAGE_SIZE, cursor: sessionCursor}),
            });
            if (!response.ok) throw new Error('会话列表加载失败');
            const payload = await response.json();
            const remote = (payload.sessions || []).map(sessionFromServer);
            setSessions((current) => {
                const existing = new Set(current.map((session) => session.id));
                return [...current, ...remote.filter((session) => !existing.has(session.id))];
            });
            setSessionHasMore(Boolean(payload.hasMore));
            setSessionCursor(payload.nextCursor || null);
        } catch (error) {
            appendMessage(activeSessionId, {
                id: createId(),
                role: 'system',
                content: '加载更多会话出现异常，请稍后重试。',
            });
        } finally {
            loadingMoreRef.current = false;
            setLoadingMoreSessions(false);
        }
    };

    const loadMore = useLatest(handleLoadMoreSessions);
    useEffect(() => {
        const target = loadMoreSentinelRef.current;
        if (!target || !sessionHasMore || typeof IntersectionObserver === 'undefined') {
            return undefined;
        }
        const observer = new IntersectionObserver(
            (entries) => {
                if (entries.some((entry) => entry.isIntersecting)) loadMore.current();
            },
            {root: target.closest(`.${PREFIX}__session-list`), rootMargin: '80px 0px'}
        );
        observer.observe(target);
        return () => observer.disconnect();
    }, [sessionHasMore, sessionCursor, loadingMoreSessions, loadMore]);

    const appendMessage = (sessionId, message) =>
        dispatch({type: 'session/message', sessionId, message, at: Date.now()});

    const handleNewSession = () => {
        const session = createSession();
        setSessions((current) => [session, ...current]);
        setActiveSessionId(session.id);
        setInputValue('');
        window.setTimeout(() => inputRef.current?.focus(), 0);
    };

    const handleDeleteSession = async (sessionId) => {
        const target = sessions.find((session) => session.id === sessionId);
        setOpenSessionMenuId(null);
        if (!window.confirm('删除这条会话？历史消息和产物将从列表中归档。')) {
            return;
        }
        if (target?.persisted) {
            const response = await apiRequest('/api/session/delete', {
                method: 'POST',
                body: JSON.stringify({sessionId}),
            });
            if (!response.ok) {
                const error = await response.json().catch(() => ({}));
                window.alert(error.message || '删除会话失败');
                return;
            }
        }
        const remaining = sessions.filter((session) => session.id !== sessionId);
        if (remaining.length) {
            setSessions(remaining);
            if (activeSessionId === sessionId) {
                setActiveSessionId(remaining[0].id);
            }
        } else {
            const next = createSession();
            setSessions([next]);
            setActiveSessionId(next.id);
        }
    };

    const openSessionMenu = (event, sessionId) => {
        event.stopPropagation();
        const rect = event.currentTarget.getBoundingClientRect();
        setSessionMenuPosition({
            top: Math.min(rect.bottom + 8, window.innerHeight - 116),
            left: Math.max(8, rect.right - 120),
        });
        setOpenSessionMenuId(sessionId);
    };

    const handleRenameStart = (session) => {
        setRenameSessionId(session.id);
        setRenameValue(session.title);
        setOpenSessionMenuId(null);
    };

    const saveRename = async (session) => {
        const title = Array.from(renameValue.trim()).slice(0, MAX_SESSION_TITLE_LENGTH).join('');
        setRenameSessionId(null);
        if (!title || title === session.title) return;
        try {
            const response = await apiRequest('/api/session/rename', {
                method: 'POST',
                body: JSON.stringify({sessionId: session.id, title}),
            });
            if (!response.ok) throw new Error('rename failed');
            updateSession(session.id, (current) => ({...current, title}));
        } catch (error) {
            appendMessage(session.id, {
                id: createId(),
                role: 'system',
                content: '重命名出现异常，请重新加载会话确认。',
            });
        }
    };

    const handleTogglePin = async (session) => {
        const pinned = !session.pinned;
        setOpenSessionMenuId(null);
        try {
            const response = await apiRequest('/api/session/pin', {
                method: 'POST',
                body: JSON.stringify({sessionId: session.id, pinned}),
            });
            if (!response.ok) throw new Error('pin failed');
            updateSession(session.id, (current) => ({...current, pinned}));
        } catch (error) {
            appendMessage(session.id, {
                id: createId(),
                role: 'system',
                content: '置顶操作出现异常，请重新加载会话确认。',
            });
        }
    };

    useEffect(() => {
        const closeOutside = (event) => {
            if (!event.target.closest(`.${PREFIX}__session-more, .${PREFIX}__session-menu`)) {
                setOpenSessionMenuId(null);
            }
        };
        const close = () => setOpenSessionMenuId(null);
        document.addEventListener('pointerdown', closeOutside);
        window.addEventListener('resize', close);
        return () => {
            document.removeEventListener('pointerdown', closeOutside);
            window.removeEventListener('resize', close);
        };
    }, []);
    return {
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
    };
}
