export const PREFIX = 'horizen-console';

export const SESSION_PAGE_SIZE = 20;

export const MAX_SESSION_TITLE_LENGTH = 20;

export const WELCOME_AVATAR_URL = new URL('../assets/horizen-mark.svg', import.meta.url).href;

export const SUGGESTIONS = [
    '介绍一下你可以帮助我完成哪些任务',
    '帮我规划一个 Java Agent 项目的实现步骤',
    '解释 Session、Turn 和工具调用之间的关系',
];

export const taskIdFromOutput = (value) => {
    const match = String(value || '').match(/task_id:\s*(task_[A-Za-z0-9-]+)/);
    return match ? match[1] : null;
};

export const listPresentations = (payload) =>
    Array.isArray(payload?.presentations) ? payload.presentations : [];

export const listMessages = (payload) => (Array.isArray(payload?.messages) ? payload.messages : []);

export const createId = () => {
    if (window.crypto?.randomUUID) {
        return window.crypto.randomUUID();
    }
    return `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

export const createSession = () => ({
    id: createId(),
    title: '新对话',
    updatedAt: Date.now(),
    messages: [],
    persisted: false,
    historyLoaded: true,
    pinned: false,
});

export const sessionFromServer = (session) => ({
    id: session.sessionId,
    title: session.title || '新对话',
    pinned: Boolean(session.pinned),
    updatedAt: Date.parse(session.lastMessageAt || session.updatedAt || session.createdAt) || 0,
    executionStatus: session.status || 'idle',
    currentTurnId: session.activeTurnId || session.lastTurnId || null,
    messages: [],
    persisted: true,
    historyLoaded: false,
});

export const groupSessionsByTime = (sessions, now = Date.now()) => {
    const todayStart = new Date(now);
    todayStart.setHours(0, 0, 0, 0);
    const recentStart = new Date(todayStart);
    recentStart.setDate(recentStart.getDate() - 6);
    const groups = {today: [], recent: [], older: []};
    sessions.forEach((session) => {
        const timestamp = Number(session.updatedAt) || 0;
        const key =
            timestamp >= todayStart.getTime()
                ? 'today'
                : timestamp >= recentStart.getTime()
                    ? 'recent'
                    : 'older';
        groups[key].push(session);
    });
    return [
        {key: 'today', label: '今日'},
        {key: 'recent', label: '近7日'},
        {key: 'older', label: '7天前'},
    ]
        .map((group) => ({...group, sessions: groups[group.key]}))
        .filter((group) => group.sessions.length);
};

export const copyText = async (text) => {
    if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
        return;
    }
    const input = document.createElement('textarea');
    input.value = text;
    input.style.position = 'fixed';
    input.style.opacity = '0';
    document.body.appendChild(input);
    input.select();
    document.execCommand('copy');
    input.remove();
};

export const statusLabel = (status) => {
    if (status === 'ended') return '已结束';
    if (status === 'needs_parent') return '交由主 Agent 处理';
    if (status === 'interrupted') return '未完成';
    if (status === 'unknown') return '结果未知';
    if (status === 'running') {
        return '运行中';
    }
    if (status === 'success') {
        return '完成';
    }
    if (status === 'cancelled') {
        return '已停止';
    }
    return '失败';
};

export const formatDuration = (milliseconds) => {
    if (milliseconds === null || milliseconds === undefined || Number.isNaN(milliseconds)) {
        return null;
    }
    if (milliseconds < 100) {
        return '<0.1s';
    }
    if (milliseconds < 10000) {
        return `${(milliseconds / 1000).toFixed(1)}s`;
    }
    return `${Math.round(milliseconds / 1000)}s`;
};

export const withoutMockLabel = (text) =>
    String(text || '')
        .replace(/^【Mock 演示数据】\s*/, '')
        .trim();
