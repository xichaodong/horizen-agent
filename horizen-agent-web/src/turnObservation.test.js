import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import renderer, {act} from 'react-test-renderer';
import {useTurnObservation} from './hooks/useTurnObservation.js';
import {TurnStreamRegistry} from './stream/TurnStreamRegistry.js';

function fixture(followSessionResponse) {
    const session = {id: 'session-a', persisted: true, historyLoaded: true, messages: []};
    const observing = [];
    return {
        messages: [],
        activeSessionId: session.id,
        status: {ready: true},
        activeSession: session,
        sessionsRef: {current: [session]},
        streams: new TurnStreamRegistry((id, running) => observing.push([id, running])),
        turnIdsRef: {current: new Map()},
        streamSequencesRef: {current: new Map()},
        loadSubtasks: () => {
        },
        updateSession: (id, update) => {
            Object.assign(session, update(session));
        },
        applyTurnEvent: () => {
        },
        showConnectionNotice: () => {
            throw new Error('Unexpected recovery notice');
        },
        followSessionResponse,
        observing,
    };
}

function HookHost({options}) {
    useTurnObservation(options);
    return null;
}

test('the real recovery hook registers the current turn and subscribes without missing bindings', async () => {
    const fetchBefore = globalThis.fetch;
    const windowBefore = globalThis.window;
    globalThis.window = globalThis;
    const requests = [];
    let followed;
    const options = fixture(async (...args) => {
        followed = args;
    });
    globalThis.fetch = async (url, init) => {
        requests.push({url, init});
        return {ok: true, json: async () => ({currentTurnId: 'turn-a', status: 'running'})};
    };
    let root;
    try {
        await act(async () => {
            root = renderer.create(React.createElement(HookHost, {options}));
            await new Promise((resolve) => setImmediate(resolve));
        });
        assert.deepEqual(
            requests.map((value) => value.url),
            ['/api/session/query', '/api/session/subscribe']
        );
        assert.equal(options.turnIdsRef.current.get('session-a'), 'turn-a');
        assert.equal(followed[0], 'session-a');
        assert.equal(followed[4], 'turn-a');
        assert.equal(followed[3], requests[1].init.signal);
        assert.equal(options.streams.controllers.size, 0);
        assert.equal(options.observing.at(-1)[1], false);
    } finally {
        if (root) await act(async () => root.unmount());
        globalThis.fetch = fetchBefore;
        globalThis.window = windowBefore;
    }
});

test('unmounting the actual recovery hook aborts and releases its subscription', async () => {
    const fetchBefore = globalThis.fetch;
    const windowBefore = globalThis.window;
    globalThis.window = globalThis;
    let signal;
    const options = fixture((session, message, response, currentSignal) => {
        signal = currentSignal;
        return new Promise((resolve, reject) =>
            currentSignal.addEventListener(
                'abort',
                () => reject(Object.assign(new Error('detached'), {name: 'AbortError'})),
                {once: true}
            )
        );
    });
    globalThis.fetch = async () => ({
        ok: true,
        json: async () => ({currentTurnId: 'turn-a', status: 'running'}),
    });
    let root;
    try {
        await act(async () => {
            root = renderer.create(React.createElement(HookHost, {options}));
            await new Promise((resolve) => setImmediate(resolve));
        });
        assert.equal(signal.aborted, false);
        await act(async () => root.unmount());
        root = null;
        assert.equal(signal.aborted, true);
        assert.equal(options.streams.controllers.size, 0);
    } finally {
        if (root) await act(async () => root.unmount());
        globalThis.fetch = fetchBefore;
        globalThis.window = windowBefore;
    }
});

test('stale stream completion cannot clear a newer subscription in the same session', () => {
    const observing = new Map();
    const streams = new TurnStreamRegistry((id, running) => observing.set(id, running));
    const first = streams.begin('a');
    const second = streams.begin('a');
    assert.equal(first.signal.aborted, true);
    streams.finish('a', first);
    assert.equal(streams.controllers.get('a'), second);
    assert.equal(observing.get('a'), true);
    streams.begin('b');
    streams.stop('a');
    assert.equal(second.signal.aborted, true);
    assert.equal(observing.get('a'), false);
    assert.equal(observing.get('b'), true);
    streams.close();
});
