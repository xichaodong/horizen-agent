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

const TodoCard = ({ message }) => {
    const tasks = message.tasks || [];
    const completed = tasks.filter((task) => task.status === 'completed').length;
    const label = (status) =>
        ({ pending: '待开始', in_progress: '进行中', completed: '已完成' })[status] || status;
    return (
        <article className={`${PREFIX}__todo-block`}>
            <div className={`${PREFIX}__todo-head`}>
                <span>执行进度</span>
                <span>
                    {completed}/{tasks.length}
                </span>
            </div>
            <ol className={`${PREFIX}__todo-list`}>
                {tasks.map((task) => (
                    <li key={task.id || task.content} className={`is-${task.status}`}>
                        <span className={`${PREFIX}__todo-state`} aria-hidden="true" />
                        <span className={`${PREFIX}__todo-content`}>{task.content}</span>
                        <span className={`${PREFIX}__todo-status`}>{label(task.status)}</span>
                    </li>
                ))}
            </ol>
        </article>
    );
};

export default TodoCard;
