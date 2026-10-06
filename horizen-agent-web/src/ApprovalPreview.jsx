import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import ApprovalCard from './ApprovalCard';
import './console.less';

const initial = {
    id: 'demo',
    status: 'waiting',
    approvals: [
        {
            approvalId: 'demo-approval',
            toolName: 'scripted_dangerous_action',
            arguments: {value: 'approval publish report'},
            presentation: {
                title: '确认执行演示操作',
                reason: '演示工具配置为执行前需要确认。',
                impact: '此操作仅生成模拟回执，不会修改业务数据。',
                fields: {操作内容: 'approval publish report', 数据来源: '本地模拟'},
            },
        },
    ],
};

function Preview() {
    const [message, setMessage] = useState(initial);
    return (
        <main
            className="horizen-console"
            style={{
                display: 'block',
                height: '100vh',
                overflow: 'auto',
                padding: '48px 20px',
                background: '#f8f9fd',
            }}
        >
            <div style={{maxWidth: 680, margin: '0 auto'}}>
                <p style={{fontSize: 12, color: '#65738a'}}>HORIZEN AGENT / 交互预览</p>
                <h1 style={{fontSize: 24, marginBottom: 12}}>执行前，确认这次操作</h1>
                <p style={{fontSize: 14, color: '#65738a', marginBottom: 28}}>
                    与聊天页共用审批组件。本页使用模拟数据，按钮只切换演示状态。
                </p>
                <ApprovalCard
                    message={message}
                    onDecide={async (value) => {
                        setMessage({
                            ...message,
                            status: value.decision === 'approve' ? 'approved' : 'denied',
                        });
                    }}
                />
                {message.status !== 'waiting' && (
                    <p role="status" style={{fontSize: 14}}>
                        {message.status === 'approved'
                            ? '模拟回执：演示操作已完成。'
                            : '该操作未执行，Agent 可以继续处理其他任务。'}
                    </p>
                )}
                <button
                    onClick={() => setMessage(initial)}
                    style={{
                        marginTop: 20,
                        padding: '8px 16px',
                        border: '1px solid #dde4ef',
                        borderRadius: 6,
                        background: 'white',
                        cursor: 'pointer',
                    }}
                >
                    重置演示
                </button>
            </div>
        </main>
    );
}

createRoot(document.getElementById('root')).render(<Preview/>);
