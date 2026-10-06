import React, {useEffect, useState} from 'react';
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
import ActivityItem from './ActivityItem.jsx';

const SubagentExecutionCard = ({message}) => {
    const [expanded, setExpanded] = useState(message.status === 'running');
    const agentName = message.agentId || message.source || '子 Agent';
    return (
        <article
            className={`${PREFIX}__subagent-tree-node is-${message.status || 'running'}`}
            style={{'--subagent-depth': Math.min(Math.max(message.depth || 1, 1), 4)}}
        >
            <details open={expanded} onToggle={(event) => setExpanded(event.currentTarget.open)}>
                <summary className={`${PREFIX}__subagent-tree-head`}>
                    <span className={`${PREFIX}__subagent-tree-branch`} aria-hidden="true"/>
                    <span className={`${PREFIX}__subagent-tree-title`}>{agentName}</span>
                    <span
                        className={`${PREFIX}__subagent-tree-status is-${message.status || 'running'}`}
                    >
                        {statusLabel(message.status || 'running')}
                    </span>
                    <DownOutlined className={`${PREFIX}__timeline-chevron`}/>
                </summary>
                <div className={`${PREFIX}__subagent-tree-meta`}>
                    {message.taskId ? <span>任务 {message.taskId}</span> : null}
                    {message.source ? <span>来源 {message.source}</span> : null}
                </div>
                <div className={`${PREFIX}__subagent-tree-steps`}>
                    {(message.steps || []).map((step) => (
                        <ActivityItem message={step} key={step.id}/>
                    ))}
                    {message.result ? (
                        <div className={`${PREFIX}__subagent-tree-result`}>
                            <MessageContent content={message.result}/>
                        </div>
                    ) : null}
                    {!message.steps?.length && !message.result ? (
                        <div className={`${PREFIX}__text-small`}>等待子 Agent 返回事件…</div>
                    ) : null}
                </div>
            </details>
        </article>
    );
};

export default SubagentExecutionCard;
