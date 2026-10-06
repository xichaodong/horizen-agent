import {useRef, useEffect} from 'react';

/** 把文本增量聚合为会话级显示更新，结束或卸载时清理计时器与待输出片段。 */
export function useTypewriter(setSessions, sessionsRef, updateSession) {
    const pendingTextRef = useRef(new Map());
    const receivedTextRef = useRef(new Map());
    const textAnimationRef = useRef(null);
    const scheduleTextAnimation = () => {
        if (textAnimationRef.current !== null) return;
        textAnimationRef.current = window.requestAnimationFrame(() => {
            textAnimationRef.current = null;
            setSessions((current) =>
                current.map((session) => ({
                    ...session,
                    messages: session.messages.map((message) => {
                        const key = `${session.id}:${message.id}`;
                        const target = pendingTextRef.current.get(key);
                        if (target === undefined) return message;
                        const visible = String(message.content || '').length;
                        const remaining = target.length - visible;
                        if (remaining <= 0) {
                            pendingTextRef.current.delete(key);
                            return message;
                        }
                        // 积压越多，每帧展示越多；落后过大时直接追平，文字本身不丢失。
                        const count =
                            remaining > 1200
                                ? remaining
                                : Math.min(remaining, Math.max(2, Math.ceil(remaining / 8)));
                        const content = target.slice(0, visible + count);
                        if (content.length === target.length) pendingTextRef.current.delete(key);
                        return {...message, content};
                    }),
                }))
            );
            if (pendingTextRef.current.size) scheduleTextAnimation();
        });
    };

    const queueTextDelta = (sessionId, messageId, text) => {
        if (!text) return;
        const key = `${sessionId}:${messageId}`;
        const visible =
            sessionsRef.current
                .find((session) => session.id === sessionId)
                ?.messages.find((message) => message.id === messageId)?.content || '';
        const received = receivedTextRef.current.get(key);
        const target = `${received === undefined ? visible : received}${text}`;
        receivedTextRef.current.set(key, target);
        pendingTextRef.current.set(key, target);
        scheduleTextAnimation();
    };

    const clearQueuedText = (sessionId, messageId) => {
        const key = `${sessionId}:${messageId}`;
        pendingTextRef.current.delete(key);
        receivedTextRef.current.delete(key);
    };

    const flushQueuedText = (sessionId, messageId) => {
        const key = `${sessionId}:${messageId}`;
        const target = pendingTextRef.current.get(key);
        if (target === undefined) return;
        pendingTextRef.current.delete(key);
        receivedTextRef.current.delete(key);
        updateSession(sessionId, (session) => ({
            ...session,
            messages: session.messages.map((message) =>
                message.id === messageId ? {...message, content: target} : message
            ),
        }));
    };

    useEffect(
        () => () => {
            if (textAnimationRef.current !== null) {
                window.cancelAnimationFrame(textAnimationRef.current);
            }
        },
        []
    );

    return {queueTextDelta, clearQueuedText, flushQueuedText};
}
