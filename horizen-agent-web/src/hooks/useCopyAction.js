import {useState, useEffect, useRef} from 'react';
import {copyText} from '../utils/chat.js';

/** 复制指定消息正文并短暂显示复制完成状态，卸载时清理提示计时器。 */
export function useCopyAction() {
    const [copiedId, setCopiedId] = useState('');
    const timer = useRef(null);
    useEffect(() => () => window.clearTimeout(timer.current), []);
    const handleCopy = async (message) => {
        await copyText(message.content);
        setCopiedId(message.id);
        window.clearTimeout(timer.current);
        timer.current = window.setTimeout(() => setCopiedId(''), 1200);
    };
    return {copiedId, handleCopy};
}
