import test from 'node:test';
import assert from 'node:assert/strict';
import { sessionsReducer } from './state/sessions.js';
test('an old stream ending only clears its own session running state', () => {
    let sessions = [
        { id: 'a', observing: true, messages: [] },
        { id: 'b', observing: true, messages: [] },
    ];
    sessions = sessionsReducer(sessions, {
        type: 'session/observing',
        sessionId: 'a',
        value: false,
    });
    assert.equal(sessions[0].observing, false);
    assert.equal(sessions[1].observing, true);
});
