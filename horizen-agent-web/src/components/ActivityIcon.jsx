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

const ActivityIcon = ({ message }) => {
    if (message.status === 'running') {
        return <LoadingOutlined className={`${PREFIX}__timeline-icon is-running`} />;
    }
    if (message.status !== 'success') {
        return <CloseCircleFilled className={`${PREFIX}__timeline-icon is-error`} />;
    }
    return message.activityType === 'tool' ? (
        <ToolOutlined className={`${PREFIX}__timeline-icon is-tool`} />
    ) : (
        <CheckCircleFilled className={`${PREFIX}__timeline-icon is-success`} />
    );
};

export default ActivityIcon;
