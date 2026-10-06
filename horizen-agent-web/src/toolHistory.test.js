import test from 'node:test';
import assert from 'node:assert/strict';
import {finishTool, appendToolOutput, toolSnapshot, finishSubagent} from './toolHistory.js';

test('tool failure has a visible generic notice without ending the parent task', () => {
    const step = finishTool({output: 'synthetic failure detail'}, {status: 'error'});
    assert.match(step.content, /工具执行出现异常/);
    assert.equal(step.output, 'synthetic failure detail');
    assert.equal(step.status, 'error');
});

test('complete durable facts replace the displayed chunks instead of appending', () => {
    const event = {
        status: 'success',
        durationMs: 12,
        details: JSON.stringify({
            toolCallSnapshot: {version: 1, input: '{"value":7}', output: 'complete result'},
        }),
    };
    const restored = finishTool({input: '{"value":', output: 'partial'}, event);
    assert.equal(restored.input, '{"value":7}');
    assert.equal(restored.output, 'complete result');
    assert.equal(restored.status, 'success');
});

test('resume output starts fresh while keeping the frozen input', () => {
    let step = finishTool(
        {},
        {
            status: 'running',
            details: {
                toolCallSnapshot: {
                    version: 1,
                    input: '{"question":"scope"}',
                    output: 'waiting for user',
                },
            },
        }
    );
    step = appendToolOutput(step, 'saved');
    step = appendToolOutput(step, ' successfully');
    step = finishTool(step, {status: 'success', details: null});
    assert.equal(step.input, '{"question":"scope"}');
    assert.equal(step.output, 'saved successfully');
});

test('legacy tool_end retains its accumulated input/output', () => {
    assert.equal(finishTool({input: '{}', output: 'legacy'}, {details: null}).output, 'legacy');
    assert.equal(toolSnapshot('not-json'), null);
    assert.equal(toolSnapshot({toolCallSnapshot: {version: 99}}), null);
});

test('missing input preserves prior input and an empty complete result clears old output', () => {
    const result = finishTool(
        {input: '{"frozen":true}', output: 'old'},
        {details: {toolCallSnapshot: {version: 1, output: ''}}}
    );
    assert.equal(result.input, '{"frozen":true}');
    assert.equal(result.output, '');
});

test('ending a child closes unfinished steps without claiming they succeeded', () => {
    const result = finishSubagent(
        {status: 'running', steps: [{status: 'running'}, {status: 'success'}]},
        'ended',
        true
    );
    assert.equal(result.status, 'ended');
    assert.deepEqual(
        result.steps.map((step) => step.status),
        ['interrupted', 'success']
    );
});

test('parent cancellation closes nested steps even if the child end event already arrived', () => {
    const result = finishSubagent(
        {status: 'ended', steps: [{status: 'running'}, {status: 'denied'}]},
        'cancelled'
    );
    assert.equal(result.status, 'ended');
    assert.deepEqual(
        result.steps.map((step) => step.status),
        ['cancelled', 'denied']
    );
});
