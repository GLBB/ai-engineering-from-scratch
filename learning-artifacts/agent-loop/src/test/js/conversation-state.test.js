const test = require('node:test');
const assert = require('node:assert/strict');
const state = require('../../main/resources/static/conversation-state.js');

test('new conversation permits mode selection', () => {
  assert.equal(state.select(state.create(), 'REWOO').mode, 'REWOO');
  assert.equal(state.create('unknown').mode, 'REACT');
});
test('mode is fixed only while running and selectable again after success or failure', () => {
  let current = state.started(state.create(), { requestId: 'one', mode: 'REWOO' });
  assert.equal(state.select(current, 'PLAN_EXECUTE').mode, 'REWOO');
  current = state.status(current, { mode: 'REWOO', running: false });
  assert.equal(state.select(current, 'PLAN_EXECUTE').mode, 'PLAN_EXECUTE');
  assert.equal(current.activeRequestId, null);
});
test('status recovers accepted request after losing run_started', () => {
  const current = state.status(state.create(), { mode: 'PLAN_EXECUTE', running: true, activeRequestId: 'one' });
  assert.equal(current.mode, 'PLAN_EXECUTE');
  assert.equal(current.running, true);
  assert.equal(current.activeRequestId, 'one');
});
test('rejected validation leaves mode selectable', () => {
  const current = state.status(state.create('REWOO'), { mode: null, running: false });
  assert.equal(current.mode, 'REWOO');
  assert.equal(state.select(current, 'REACT').mode, 'REACT');
});
test('stale request start cannot replace active request', () => {
  const current = state.started(state.create(), { requestId: 'two', mode: 'REACT' });
  assert.deepEqual(state.started(current, { requestId: 'one', mode: 'REACT' }), current);
});
test('new conversation resets execution and retains preferred mode', () => {
  const old = state.started(state.create(), { requestId: 'one', mode: 'REWOO' });
  const fresh = state.create(old.mode);
  assert.equal(fresh.running, false);
  assert.equal(fresh.mode, 'REWOO');
});
test('idle status preserves selection for the next query instead of restoring the last run mode', () => {
  const selected = state.select(state.create(), 'PLAN_EXECUTE');
  const current = state.status(selected, { mode: 'REACT', running: false });
  assert.equal(current.mode, 'PLAN_EXECUTE');
});
test('another accepted query can start in a different mode in the same conversation', () => {
  let current = state.started(state.create(), { requestId: 'one', mode: 'REACT' });
  current = state.status(current, { mode: 'REACT', running: false });
  current = state.select(current, 'REWOO');
  current = state.started(current, { requestId: 'two', mode: 'REWOO' });
  assert.equal(current.mode, 'REWOO');
  assert.equal(current.activeRequestId, 'two');
});
