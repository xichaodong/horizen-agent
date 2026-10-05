import React, { useEffect, useState } from 'react';
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
} from '../utils/chat.js';
import MessageContent from '../MessageContent.jsx';
import ActivityIcon from './ActivityIcon.jsx';

const ActivityItem = ({ message, onRetry }) => {
    const hasBody = Boolean(message.content || message.input || message.output || message.details);
    const [expanded, setExpanded] = useState(
        message.activityType === 'reasoning' ||
            message.activityType === 'error' ||
            message.activityType === 'notice' ||
            ['error', 'failed', 'interrupted'].includes(message.status)
    );
    const [clock, setClock] = useState(Date.now());
    useEffect(() => {
        if (message.status !== 'running' || !message.startedAt) {
            return undefined;
        }
        const timer = window.setInterval(() => setClock(Date.now()), 100);
        return () => window.clearInterval(timer);
    }, [message.status, message.startedAt]);
    const duration = formatDuration(
        message.durationMs ??
            (message.status === 'running' && message.startedAt ? clock - message.startedAt : null)
    );
    return (
        <article className={`${PREFIX}__timeline-item is-${message.activityType || 'turn'}`}>
            <span className={`${PREFIX}__timeline-rail`} aria-hidden="true" />
            <span className={`${PREFIX}__timeline-node`}>
                <ActivityIcon message={message} />
            </span>
            <div className={`${PREFIX}__timeline-main`}>
                {hasBody ? (
                    <details
                        className={`${PREFIX}__timeline-details`}
                        open={expanded}
                        onToggle={(event) => setExpanded(event.currentTarget.open)}
                    >
                        <summary>
                            <span className={`${PREFIX}__timeline-title`}>{message.title}</span>
                            {message.toolName ? <code>{message.toolName}</code> : null}
                            {duration ? (
                                <span className={`${PREFIX}__timeline-duration`}>{duration}</span>
                            ) : null}
                            <span className={`${PREFIX}__timeline-status is-${message.status}`}>
                                {statusLabel(message.status)}
                            </span>
                            <DownOutlined className={`${PREFIX}__timeline-chevron`} />
                        </summary>
                        {message.content ? (
                            <div className={`${PREFIX}__timeline-copy`}>
                                <MessageContent content={message.content} />
                            </div>
                        ) : null}
                        {message.input ? (
                            <div className={`${PREFIX}__timeline-payload`}>
                                <span>输入</span>
                                <pre>{message.input}</pre>
                            </div>
                        ) : null}
                        {message.output ? (
                            <div className={`${PREFIX}__timeline-payload`}>
                                <span>输出</span>
                                <pre>{message.output}</pre>
                            </div>
                        ) : null}
                        {message.details ? (
                            <div className={`${PREFIX}__timeline-meta`}>{message.details}</div>
                        ) : null}
                        {message.activityType === 'error' && onRetry ? (
                            <button
                                type="button"
                                className={`${PREFIX}__retry-button`}
                                onClick={onRetry}
                            >
                                <ReloadOutlined /> 重新执行
                            </button>
                        ) : null}
                    </details>
                ) : (
                    <div className={`${PREFIX}__timeline-summary`}>
                        <span className={`${PREFIX}__timeline-title`}>{message.title}</span>
                        {duration ? (
                            <span className={`${PREFIX}__timeline-duration`}>{duration}</span>
                        ) : null}
                        <span className={`${PREFIX}__timeline-status is-${message.status}`}>
                            {statusLabel(message.status)}
                        </span>
                    </div>
                )}
            </div>
        </article>
    );
};

export default ActivityItem;
