const test = require('node:test');
const assert = require('node:assert/strict');
const state = require('../../main/resources/static/conversation-state.js');

test('new conversation permits mode selection', () => {
  assert.equal(state.select(state.create(), 'REWOO').mode, 'REWOO');
  assert.equal(state.create('unknown').mode, 'REACT');
});
test('acceptance permanently locks mode, including after failed run', () => {
  let current = state.started(state.create(), { requestId: 'one', mode: 'REWOO' });
  current = state.status(current, { mode: 'REWOO', modeLocked: true, running: false });
  assert.equal(state.select(current, 'PLAN_EXECUTE').mode, 'REWOO');
  assert.equal(current.modeLocked, true);
});
test('status recovers accepted request after losing run_started', () => {
  const current = state.status(state.create(), { mode: 'PLAN_EXECUTE', modeLocked: true, running: true, activeRequestId: 'one' });
  assert.equal(current.mode, 'PLAN_EXECUTE');
  assert.equal(current.running, true);
  assert.equal(current.activeRequestId, 'one');
});
test('rejected validation leaves mode selectable', () => {
  const current = state.status(state.create('REWOO'), { mode: null, modeLocked: false, running: false });
  assert.equal(current.mode, 'REWOO');
  assert.equal(state.select(current, 'REACT').mode, 'REACT');
});
test('stale request start cannot replace active request', () => {
  const current = state.started(state.create(), { requestId: 'two', mode: 'REACT' });
  assert.deepEqual(state.started(current, { requestId: 'one', mode: 'REACT' }), current);
});
test('new conversation resets lock and retains preferred mode', () => {
  const old = state.started(state.create(), { requestId: 'one', mode: 'REWOO' });
  const fresh = state.create(old.mode);
  assert.equal(fresh.modeLocked, false);
  assert.equal(fresh.running, false);
  assert.equal(fresh.mode, 'REWOO');
});
