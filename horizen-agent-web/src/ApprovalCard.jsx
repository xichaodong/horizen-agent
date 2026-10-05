import React, { useRef } from 'react';

const P = 'horizen-console';
const displayValue = (value) =>
    typeof value === 'string' ? value : JSON.stringify(value, null, 2);

export default function ApprovalCard({ message, onDecide }) {
    const submitting = useRef(false);
    const decide = async (decision) => {
        if (submitting.current || message.status !== 'waiting') return;
        submitting.current = true;
        try {
            await onDecide({ ...message, decision });
        } finally {
            submitting.current = false;
        }
    };
    const expired =
        message.status === 'waiting' &&
        message.approvals.some(
            (item) => item.expiresAt && Date.parse(item.expiresAt) <= Date.now()
        );
    const status = expired
        ? '已过期，请重新发起操作'
        : {
              waiting: '等待确认',
              submitting: '正在提交',
              approved: '已同意，执行结果见后续回复',
              denied: '已拒绝',
          }[message.status];
    return (
        <article className={`${P}__approval-block`} aria-label="操作确认">
            <div className={`${P}__approval-banner ${P}__approval-detail`}>
                <div className={`${P}__approval-heading`}>
                    <strong>操作确认</strong>
                    <span role="status">{status}</span>
                </div>
                {message.approvals.map((item) => {
                    const view = item.presentation || {};
                    return (
                        <section className={`${P}__approval-operation`} key={item.approvalId}>
                            <h3>{view.title || `确认执行 ${item.toolName}`}</h3>
                            <p>{view.reason || '当前执行策略要求先确认这次操作。'}</p>
                            {view.impact && (
                                <p className={`${P}__approval-impact`}>{view.impact}</p>
                            )}
                            {Object.keys(view.fields || {}).length > 0 && (
                                <dl>
                                    {Object.entries(view.fields).map(([label, value]) => (
                                        <React.Fragment key={label}>
                                            <dt>{label}</dt>
                                            <dd>{displayValue(value)}</dd>
                                        </React.Fragment>
                                    ))}
                                </dl>
                            )}
                            <details open={!Object.keys(view.fields || {}).length}>
                                <summary>查看具体执行参数</summary>
                                <code>{item.toolName}</code>
                                <pre>{JSON.stringify(item.arguments || {}, null, 2)}</pre>
                            </details>
                            {item.expiresAt && (
                                <small>有效期至 {new Date(item.expiresAt).toLocaleString()}</small>
                            )}
                        </section>
                    );
                })}
                {message.status === 'waiting' && !expired && (
                    <div className={`${P}__approval-banner-actions`}>
                        <button type="button" onClick={() => decide('deny')}>
                            {message.approvals.length > 1 ? '全部拒绝' : '拒绝'}
                        </button>
                        <button
                            type="button"
                            className="is-approve"
                            onClick={() => decide('approve')}
                        >
                            {message.approvals.length > 1 ? '全部同意并继续' : '同意并继续'}
                        </button>
                    </div>
                )}
            </div>
        </article>
    );
}
