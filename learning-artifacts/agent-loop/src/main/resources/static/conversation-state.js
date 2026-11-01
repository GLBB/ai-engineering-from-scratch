/* Pure state transitions shared by the browser and Node's built-in test runner. */
(function (root) {
  const modes = ['REACT', 'REWOO', 'PLAN_EXECUTE'];
  function create(preferred = 'REACT') {
    return { mode: modes.includes(preferred) ? preferred : 'REACT', modeLocked: false, running: false, activeRequestId: null };
  }
  function status(state, data) {
    return { mode: data.modeLocked ? data.mode : state.mode, modeLocked: data.modeLocked,
      running: data.running, activeRequestId: data.activeRequestId || null };
  }
  function started(state, event) {
    if (state.activeRequestId && state.activeRequestId !== event.requestId) return state;
    if (state.modeLocked && state.mode !== event.mode) return state;
    return { mode: event.mode, modeLocked: true, running: true, activeRequestId: event.requestId };
  }
  function select(state, mode) {
    return state.modeLocked || state.running || !modes.includes(mode) ? state : { ...state, mode };
  }
  const api = { create, status, started, select };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.conversationState = api;
})(typeof globalThis === 'undefined' ? this : globalThis);
