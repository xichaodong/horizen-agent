/** Session-scoped updates keep background execution from changing another session's controls. */
/** 按会话操作更新目录与消息状态；返回新状态，避免修改其他会话的观察标记。 */
export function sessionsReducer(sessions, action) {
    switch (action.type) {
        case 'catalog/replace':
            return typeof action.sessions === 'function'
                ? action.sessions(sessions)
                : action.sessions;
        case 'session/update':
            return sessions.map((session) =>
                session.id === action.sessionId ? action.update(session) : session
            );
        case 'session/message':
            return sessions.map((session) =>
                session.id === action.sessionId
                    ? {
                        ...session,
                        updatedAt: action.at,
                        messages: [...session.messages, action.message],
                    }
                    : session
            );
        case 'session/observing':
            return sessions.map((session) =>
                session.id === action.sessionId ? {...session, observing: action.value} : session
            );
        default:
            return sessions;
    }
}
