import test from 'node:test';
import assert from 'node:assert/strict';
import { createTurnEventProcessor, createActivityUpdater } from './stream/turnEvents.js';
import { sessionsReducer } from './state/sessions.js';

test('replayed events are deduplicated per session and terminal facts settle only their own turn', () => {
    let sessions = [
        { id: 'a', messages: [] },
        { id: 'b', messages: [], executionStatus: 'running' },
    ];
    const updateSession = (sessionId, update) => {
        sessions = sessionsReducer(sessions, { type: 'session/update', sessionId, update });
    };
    const processor = createTurnEventProcessor({
        updateSession,
        upsertActivity: createActivityUpdater(updateSession),
        streamSequencesRef: { current: new Map() },
        turnIdsRef: { current: new Map() },
        queueTextDelta() {},
        loadSubtasks() {},
        appendMessage() {},
        upsertPresentation() {},
        loadApprovals() {},
        flushQueuedText() {},
        clearQueuedText() {},
    });
    const started = { type: 'turn_start', id: 'turn-a', streamSequence: 1 };
    processor.applyTurnEvent('a', 'reply-a', started);
    const count = sessions[0].messages.length;
    processor.applyTurnEvent('a', 'reply-a', started);
    assert.equal(sessions[0].messages.length, count);
    processor.applyTurnEvent('a', 'reply-a', {
        type: 'done',
        id: 'turn-a',
        text: 'Synthetic result',
        streamSequence: 2,
    });
    assert.equal(sessions[0].executionStatus, 'completed');
    assert.equal(sessions[0].messages.at(-1).content, 'Synthetic result');
    assert.equal(sessions[1].executionStatus, 'running');
});
