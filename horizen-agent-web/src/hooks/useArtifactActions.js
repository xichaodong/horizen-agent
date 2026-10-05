import { useState } from 'react';
import { request as apiRequest } from '../api/client.js';
import { createId } from '../utils/chat.js';
/** 管理本次输入附件的上传与产物打开操作，运行期间避免重复上传。 */
export function useArtifactActions({ activeSessionId, appendMessage, running }) {
    const [attachments, setAttachments] = useState([]);
    const [uploading, setUploading] = useState(false);
    const handleUpload = async (event) => {
        const file = event.target.files?.[0];
        event.target.value = '';
        if (!file || uploading || running) {
            return;
        }
        setUploading(true);
        try {
            const form = new FormData();
            form.append('file', file);
            const response = await apiRequest('/api/artifacts/upload', {
                method: 'POST',
                body: form,
            });
            if (!response.ok) {
                const payload = await response.json().catch(() => ({}));
                throw new Error(payload.error || `上传失败（${response.status}）`);
            }
            const artifact = await response.json();
            setAttachments((current) => [...current, artifact]);
        } catch (error) {
            appendMessage(activeSessionId, {
                id: createId(),
                role: 'system',
                content: error.message || '文件上传失败',
            });
        } finally {
            setUploading(false);
        }
    };

    const openArtifact = async (artifact) => {
        try {
            const response = await apiRequest('/api/artifacts/download-url', {
                method: 'POST',
                body: JSON.stringify({ artifactId: artifact.artifactId, expiresInSeconds: -1 }),
            });
            if (!response.ok) {
                throw new Error('无法获取下载地址');
            }
            const payload = await response.json();
            window.open(payload.url, '_blank', 'noopener,noreferrer');
        } catch (error) {
            appendMessage(activeSessionId, {
                id: createId(),
                role: 'system',
                content: error.message || '打开产物失败',
            });
        }
    };
    return { attachments, setAttachments, uploading, handleUpload, openArtifact };
}
