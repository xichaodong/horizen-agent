import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';

const apiTarget = process.env.AGENT_WEB_API_TARGET || 'http://127.0.0.1:8787';
const devPort = Number(process.env.AGENT_WEB_DEV_PORT || 5173);

export default defineConfig({
    plugins: [react()],
    server: {
        host: '127.0.0.1',
        port: devPort,
        strictPort: true,
        proxy: {
            '/api': apiTarget,
        },
    },
});
