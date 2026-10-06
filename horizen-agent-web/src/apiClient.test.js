import test from 'node:test';
import assert from 'node:assert/strict';
import {request, post} from './api/client.js';

test('JSON and stream requests share serialization while preserving cancellation', async () => {
    const original = globalThis.fetch;
    let captured;
    globalThis.fetch = async (url, options) => {
        captured = {url, ...options};
        return {ok: true};
    };
    try {
        const signal = new AbortController().signal;
        await post('/api/session/subscribe', {sessionId: 'synthetic'}, {signal, stream: true});
        assert.equal(captured.body, '{"sessionId":"synthetic"}');
        assert.equal(captured.headers.get('Content-Type'), 'application/json');
        assert.equal(captured.headers.get('Accept'), 'text/event-stream');
        assert.equal(captured.signal, signal);
    } finally {
        globalThis.fetch = original;
    }
});

test('multipart uploads retain the body and let the browser supply its boundary', async () => {
    const original = globalThis.fetch;
    let captured;
    globalThis.fetch = async (_url, options) => {
        captured = options;
        return {ok: true};
    };
    try {
        const form = new FormData();
        form.append('synthetic', 'value');
        await request('/api/artifacts/upload', {method: 'POST', body: form});
        assert.equal(captured.body, form);
        assert.equal(captured.headers.has('Content-Type'), false);
    } finally {
        globalThis.fetch = original;
    }
});
