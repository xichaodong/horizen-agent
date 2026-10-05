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

const AskUserCard = ({ message, onSubmit }) => {
    const [answers, setAnswers] = useState({});
    const [submitting, setSubmitting] = useState(false);
    const questions = message.questions || [];
    const toggle = (question, optionId) =>
        setAnswers((current) => {
            const selected = current[question.questionId]?.selectedOptionIds || [];
            const next =
                question.type === 'single'
                    ? [optionId]
                    : selected.includes(optionId)
                      ? selected.filter((id) => id !== optionId)
                      : [...selected, optionId];
            return {
                ...current,
                [question.questionId]: {
                    questionId: question.questionId,
                    selectedOptionIds: next,
                    customText: '',
                },
            };
        });
    const setCustomText = (question, customText) =>
        setAnswers((current) => ({
            ...current,
            [question.questionId]: {
                ...(current[question.questionId] || {
                    questionId: question.questionId,
                    selectedOptionIds: [],
                }),
                customText,
            },
        }));
    const submit = async (skip) => {
        setSubmitting(true);
        try {
            await onSubmit(message, skip ? [] : Object.values(answers), skip);
        } finally {
            setSubmitting(false);
        }
    };
    return (
        <article className={`${PREFIX}__approval-block`}>
            <div className={`${PREFIX}__approval-banner ${PREFIX}__ask-user-banner`}>
                {questions.map((question) => (
                    <div key={question.questionId}>
                        <p className={`${PREFIX}__approval-copy`}>
                            {question.title}
                            {question.required ? ' *' : ''}
                        </p>
                        {question.options?.map((option) => (
                            <label className={`${PREFIX}__ask-user-option`} key={option.optionId}>
                                <input
                                    type={question.type === 'multiple' ? 'checkbox' : 'radio'}
                                    name={question.questionId}
                                    checked={(
                                        answers[question.questionId]?.selectedOptionIds || []
                                    ).includes(option.optionId)}
                                    disabled={submitting || message.status !== 'waiting'}
                                    onChange={() => toggle(question, option.optionId)}
                                />{' '}
                                {option.label}
                            </label>
                        ))}
                        <input
                            className={`${PREFIX}__ask-user-custom`}
                            type="text"
                            maxLength={2000}
                            disabled={submitting || message.status !== 'waiting'}
                            placeholder="补充说明（可选）"
                            value={answers[question.questionId]?.customText || ''}
                            onChange={(event) => setCustomText(question, event.target.value)}
                        />
                    </div>
                ))}
                <div className={`${PREFIX}__approval-banner-actions`}>
                    <button
                        type="button"
                        disabled={submitting || message.status !== 'waiting'}
                        onClick={() => submit(true)}
                    >
                        跳过
                    </button>
                    <button
                        type="button"
                        className="is-approve"
                        disabled={submitting || message.status !== 'waiting'}
                        onClick={() => submit(false)}
                    >
                        {submitting
                            ? '提交中…'
                            : message.status === 'waiting'
                              ? '提交答案'
                              : '已提交'}
                    </button>
                </div>
            </div>
        </article>
    );
};

export default AskUserCard;
