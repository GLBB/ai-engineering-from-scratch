const traceView = (() => {
  const conversation = document.querySelector('#conversation');
  const tracePage = document.querySelector('#trace-view');
  const timelineRoot = document.querySelector('#trace-timelines');
  const eventRoot = document.querySelector('#trace-events');
  const searchInput = document.querySelector('#trace-search');
  const durationLabel = document.querySelector('#trace-duration');
  const roundsLabel = document.querySelector('#trace-rounds');
  const toolsLabel = document.querySelector('#trace-tools');
  const stepsByTurn = new Map();
  const turnTimes = new Map();
  const openSteps = new Set();
  const results = new Map();
  const correlations = new Map();
  const modeNames = { REACT: 'ReAct', REWOO: 'ReWOO', PLAN_EXECUTE: 'Plan-and-Execute' };

  function turnHeading(turnNumber, steps) {
    const mode = [...steps.values()].find(step => step.mode)?.mode;
    return `第 ${turnNumber} 轮${mode ? ' · ' + (modeNames[mode] || mode) : ''}`;
  }
  function reference(data) {
    if (!/^[a-f0-9]{32}$/.test(data.traceId || '')) return null;
    const box = document.createElement('div');
    box.className = 'trace-reference';
    let url;
    try { url = new URL(data.traceUrl); } catch (_) { /* ID is useful without a configured project URL. */ }
    if (url && ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password) {
      const link = document.createElement('a');
      link.textContent = '在 Langfuse 查看本轮 Trace';
      link.href = url.href;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      box.append(link);
    }
    const id = document.createElement('code');
    id.textContent = `traceId: ${data.traceId}`;
    box.append(id);
    return box;
  }
  function started(turnNumber, data) { correlations.set(turnNumber, data); render(); }

  function formatDuration(milliseconds) {
    if (milliseconds < 1000) return `${Math.round(milliseconds)} ms`;
    return `${(milliseconds / 1000).toFixed(1)} s`;
  }

  function show(view) {
    const isTrace = view === 'trace';
    conversation.hidden = isTrace;
    tracePage.hidden = !isTrace;
    for (const tab of document.querySelectorAll('.view-tabs button')) {
      tab.setAttribute('aria-selected', String(tab.dataset.view === view));
    }
  }

  function beginTurn(turnNumber) {
    stepsByTurn.set(turnNumber, new Map());
    turnTimes.set(turnNumber, { started: performance.now(), duration: 0, finished: false });
    render();
  }

  function finishTurn(turnNumber) {
    const timing = turnTimes.get(turnNumber);
    if (!timing) return;
    timing.duration = performance.now() - timing.started;
    timing.finished = true;
    render();
  }

  function record(turnNumber, event) {
    const steps = stepsByTurn.get(turnNumber);
    if (!steps) return;
    const label = event.phase ? `${event.phase}${event.stepId ? ' / ' + event.stepId : ''} · ${event.label}` : event.label;
    steps.set(event.id, { ...steps.get(event.id), ...event, label });
    render();
  }

  function recordError(turnNumber, message) {
    record(turnNumber, {
      id: `error-${turnNumber}-${Date.now()}`,
      kind: 'error',
      status: 'error',
      round: 0,
      startedAtMs: Date.now(),
      durationMs: 0,
      label: '本轮失败',
      input: null,
      output: message,
    });
  }

  function renderStats() {
    let totalDuration = 0;
    let modelCount = 0;
    let toolCount = 0;
    let toolsExecuted = 0;
    for (const [turnNumber, steps] of stepsByTurn) {
      const timing = turnTimes.get(turnNumber);
      totalDuration += timing.finished ? timing.duration : performance.now() - timing.started;
      for (const step of steps.values()) {
        if (step.kind === 'model') modelCount++;
        if (step.kind === 'tool' && !results.has(turnNumber)) toolCount++;
      }
      if (results.has(turnNumber)) {
        toolCount += results.get(turnNumber).counters.toolAttempts;
        toolsExecuted += results.get(turnNumber).counters.toolsExecuted;
      }
    }
    durationLabel.textContent = `时长 ${formatDuration(totalDuration)}`;
    roundsLabel.textContent = `模型轮次 ${modelCount}`;
    toolsLabel.textContent = `工具尝试 ${toolCount} · 实际执行 ${toolsExecuted}`;
  }

  function renderTimelines() {
    timelineRoot.replaceChildren();
    for (const [turnNumber, steps] of stepsByTurn) {
      const timed = [...steps.values()].filter(step =>
        step.kind === 'user' || step.kind === 'model' || step.kind === 'tool');
      if (timed.length === 0) continue;

      const firstAt = Math.min(...timed.map(step => step.startedAtMs));
      const lastAt = Math.max(...timed.map(step => step.startedAtMs + Math.max(step.durationMs, 1)));
      const total = Math.max(lastAt - firstAt, 1);
      const group = document.createElement('div');
      group.className = 'timeline-group';
      const heading = document.createElement('div');
      heading.className = 'timeline-heading';
      heading.textContent = turnHeading(turnNumber, steps);
      group.append(heading);

      for (const [kind, label] of [['user', '输入'], ['model', '模型'], ['tool', '工具']]) {
        const lane = document.createElement('div');
        lane.className = 'timeline-lane';
        const laneLabel = document.createElement('span');
        laneLabel.className = 'timeline-label';
        laneLabel.textContent = label;
        const track = document.createElement('div');
        track.className = 'timeline-track';

        for (const step of timed.filter(item => item.kind === kind)) {
          const bar = document.createElement('button');
          bar.type = 'button';
          bar.className = `timeline-bar ${kind}${step.status === 'error' ? ' error' : ''}`;
          const left = (step.startedAtMs - firstAt) / total * 100;
          const width = Math.max(step.durationMs / total * 100, 1.5);
          bar.style.left = `${left}%`;
          bar.style.width = `${Math.min(width, 100 - left)}%`;
          bar.title = `${step.label} · ${formatDuration(step.durationMs)}`;
          bar.setAttribute('aria-label', bar.title);
          bar.addEventListener('click', () => {
            show('trace');
            const row = document.getElementById(`trace-${step.id}`);
            if (row) {
              row.open = true;
              openSteps.add(step.id);
              row.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
          });
          track.append(bar);
        }
        lane.append(laneLabel, track);
        group.append(lane);
      }
      timelineRoot.append(group);
    }
  }

  function preview(step) {
    const text = step.kind === 'tool'
      ? `${step.input || ''}${step.output ? `  →  ${step.output}` : ''}`
      : step.output || step.input || '';
    return text.replace(/\s+/g, ' ').slice(0, 240);
  }

  function detailBlock(label, content) {
    const wrapper = document.createElement('div');
    wrapper.className = 'trace-detail-block';
    const title = document.createElement('div');
    title.textContent = label;
    const body = document.createElement('pre');
    body.textContent = content;
    wrapper.append(title, body);
    return wrapper;
  }

  function renderStep(step, firstAt) {
    const row = document.createElement('details');
    row.className = 'trace-row';
    row.id = `trace-${step.id}`;
    row.open = openSteps.has(step.id);
    row.addEventListener('toggle', () => {
      if (row.open) openSteps.add(step.id);
      else openSteps.delete(step.id);
    });

    const summary = document.createElement('summary');
    const badge = document.createElement('span');
    const isError = step.status === 'error';
    badge.className = `trace-badge ${isError ? 'error' : step.kind}`;
    badge.textContent = isError ? '错误'
      : { user: '用户', settings: '设置', model: '模型', reasoning: '思考',
          tool: '工具', assistant: '助手' }[step.kind];
    const title = document.createElement('strong');
    title.textContent = step.label;
    const excerpt = document.createElement('span');
    excerpt.className = 'trace-preview';
    excerpt.textContent = preview(step);
    const timing = document.createElement('span');
    timing.className = 'trace-row-time';
    timing.textContent = `+${formatDuration(step.startedAtMs - firstAt)}`
      + (step.durationMs ? ` · ${formatDuration(step.durationMs)}` : '');
    summary.append(badge, title, excerpt, timing);
    row.append(summary);

    const details = document.createElement('div');
    details.className = 'trace-detail';
    if (step.input) details.append(detailBlock('输入 / 参数', step.input));
    if (step.output) details.append(detailBlock('输出 / 结果', step.output));
    if (step.usage) details.append(detailBlock('Token 用量（缺失字段为 unknown）', JSON.stringify(step.usage, null, 2)));
    if (!step.output && step.status === 'running') {
      details.append(detailBlock('状态', '执行中…'));
    }
    row.append(details);
    return row;
  }

  function renderEvents() {
    eventRoot.replaceChildren();
    const query = searchInput.value.trim().toLocaleLowerCase();
    let visible = 0;
    for (const [turnNumber, steps] of stepsByTurn) {
      const matching = [...steps.values()].filter(step =>
        `${step.label} ${step.input || ''} ${step.output || ''}`
          .toLocaleLowerCase().includes(query));
      if (matching.length === 0) continue;
      const heading = document.createElement('h2');
      heading.className = 'trace-turn-heading';
      heading.textContent = turnHeading(turnNumber, steps);
      eventRoot.append(heading);
      const link = reference(correlations.get(turnNumber) || {});
      if (link) eventRoot.append(link);
      const firstAt = Math.min(...[...steps.values()].map(step => step.startedAtMs));
      for (const step of matching) {
        eventRoot.append(renderStep(step, firstAt));
        visible++;
      }
    }
    if (visible === 0) {
      const empty = document.createElement('p');
      empty.className = 'trace-empty';
      empty.textContent = query ? '没有匹配的轨迹。' : '发送消息后，这里会显示实际发生的模型请求和工具调用。';
      eventRoot.append(empty);
    }
  }

  function render() {
    renderStats();
    renderTimelines();
    renderEvents();
  }

  for (const tab of document.querySelectorAll('.view-tabs button')) {
    tab.addEventListener('click', () => show(tab.dataset.view));
  }
  searchInput.addEventListener('input', renderEvents);
  setInterval(() => {
    if (!tracePage.hidden && [...turnTimes.values()].some(timing => !timing.finished)) {
      renderStats();
    }
  }, 500);

  function reset() {
    stepsByTurn.clear(); turnTimes.clear(); openSteps.clear(); results.clear(); correlations.clear(); searchInput.value = ''; render();
  }
  function result(turnNumber, data) { results.set(turnNumber, data); render(); }
  return { show, beginTurn, finishTurn, record, recordError, reset, result, started, reference };
})();
