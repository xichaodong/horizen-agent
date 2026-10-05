import test from 'node:test';
import assert from 'node:assert/strict';
import { followTurnStream, readEventStream, isTurnSettled } from './turnRecovery.js';

const response = (...events) =>
    new Response(events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join(''), {
        headers: { 'Content-Type': 'text/event-stream' },
    });
const start = { type: 'turn_start', id: 'turn' };

function context(overrides = {}) {
    const events = [],
        notices = [];
    return {
        events,
        notices,
        options: {
            response: response(start),
            expectedTurnId: 'turn',
            onEvent: (event) => events.push(event),
            onConnection: (notice) => notices.push(notice),
            delay: async () => {},
            queryExecution: async () => ({
                currentTurnId: 'turn',
                status: 'failed',
                failureCode: 'UNLISTED_ERROR',
            }),
            subscribe: async () => {
                throw new Error('should not subscribe');
            },
            loadHistory: async () => {
                throw new Error('history unavailable');
            },
            ...overrides,
        },
    };
}

test('unknown server failure is visible after premature EOF even when history is unavailable', async () => {
    const { events, notices, options } = context();
    await followTurnStream(options);
    assert.deepEqual(
        events.map((event) => event.type),
        ['turn_start', 'error']
    );
    assert.match(events[1].text, /异常/);
    assert.deepEqual(notices, ['recovering', 'restored']);
});

test('disconnect recovery follows the same turn without resubmitting the task', async () => {
    let subscriptions = 0;
    const { events, notices, options } = context({
        queryExecution: async () => ({ currentTurnId: 'turn', status: 'running' }),
        subscribe: async (turn) => {
            assert.equal(turn, 'turn');
            subscriptions++;
            return response({ type: 'done', id: 'turn', text: 'result' });
        },
    });
    await followTurnStream(options);
    assert.equal(subscriptions, 1);
    assert.equal(events.at(-1).text, 'result');
    assert.deepEqual(notices, ['recovering', 'restored']);
});

test('stream recovery notice does not end a still-running task', async () => {
    const { events, options } = context({
        response: response(start, {
            type: 'execution_notice',
            id: 'stream-recovery',
            status: 'unknown',
            text: '实时输出无法恢复',
        }),
        queryExecution: async () => ({ currentTurnId: 'turn', status: 'running' }),
        subscribe: async () => response({ type: 'done', id: 'turn', text: 'confirmed result' }),
    });
    await followTurnStream(options);
    assert.deepEqual(
        events.map((event) => event.type),
        ['turn_start', 'execution_notice', 'done']
    );
});

test('repeated network failures keep one recovery notice and then recover durable reply', async () => {
    let attempts = 0;
    const { events, notices, options } = context({
        queryExecution: async () => {
            if (++attempts < 3) throw new Error('network unavailable');
            return { currentTurnId: 'turn', status: 'completed' };
        },
        loadHistory: async () => ({
            messages: [{ turnId: 'turn', role: 'assistant', content: 'durable reply' }],
        }),
    });
    await followTurnStream(options);
    assert.deepEqual(notices, ['recovering', 'restored']);
    assert.equal(events.at(-1).type, 'done');
    assert.equal(events.at(-1).text, 'durable reply');
});

test('approval recovery restores interaction rather than inventing completion', async () => {
    const pending = { type: 'approval_required', id: 'approval', details: 'synthetic approval' };
    const { events, options } = context({
        queryExecution: async () => ({ currentTurnId: 'turn', status: 'waiting_approval' }),
        loadHistory: async () => ({ timelineEvents: [{ turnId: 'turn', event: pending }] }),
    });
    await followTurnStream(options);
    assert.equal(events.at(-1).type, 'approval_required');
    assert.equal(
        events.some((event) => event.type === 'done'),
        false
    );
});

test('a changed turn or previous turn never supplies a false result', async () => {
    for (const overrides of [
        { queryExecution: async () => ({ currentTurnId: 'other', status: 'completed' }) },
        { response: response(), expectedTurnId: null, previousTurnId: 'turn' },
    ]) {
        const { events, notices, options } = context(overrides);
        await followTurnStream(options);
        assert.equal(events.some(isTurnSettled), false);
        assert.equal(notices.at(-1), 'unknown');
    }
});

test('malformed SSE is reported through recovery, while intentional abort stops recovery', async () => {
    const { events, options } = context({ response: new Response('data: not-json\n\n') });
    await followTurnStream(options);
    assert.equal(events.at(-1).type, 'error');
    const controller = new AbortController();
    controller.abort();
    const aborted = context({ signal: controller.signal });
    await assert.rejects(followTurnStream(aborted.options), { name: 'AbortError' });
    assert.equal(aborted.notices.length, 0);
});

test('child failure does not settle root; a terminal root stops consumption and releases the reader', async () => {
    const events = [];
    const settled = await readEventStream(
        response(
            { type: 'error', source: 'session/worker' },
            { type: 'done', text: 'root result' },
            { type: 'error', text: 'late duplicate' }
        ),
        (event) => events.push(event)
    );
    assert.equal(settled, true);
    assert.equal(events.length, 2);
    assert.equal(isTurnSettled(events[0]), false);
});

test('truncated UTF-8 transport and abrupt reader failure both enter recovery', async () => {
    const broken = new Response(
        new ReadableStream({
            start(controller) {
                controller.enqueue(
                    new TextEncoder().encode('data: {"type":"text_delta","text":"部分')
                );
                controller.close();
            },
        })
    );
    const { events, options } = context({ response: broken });
    await followTurnStream(options);
    assert.equal(events.at(-1).type, 'error');
});
