const conversation = document.querySelector('#conversation');
const welcome = document.querySelector('#welcome');
const form = document.querySelector('#chat-form');
const promptInput = document.querySelector('#prompt');
const sendButton = document.querySelector('#send-button');
const modelSelect = document.querySelector('#model-select');
const effortSelect = document.querySelector('#effort-select');
const connectionStatus = document.querySelector('#connection-status');
const settingsDialog = document.querySelector('#settings-dialog');
const settingsForm = document.querySelector('#settings-form');
const protocolSelect = document.querySelector('#protocol-select');
const protocolStatus = document.querySelector('#protocol-status');
const settingsSave = document.querySelector('#settings-save');
const protocolNames = {
  CHAT_COMPLETIONS: 'Chat Completions',
  RESPONSES: 'Responses',
  ANTHROPIC: 'Anthropic Messages',
};

const modeSelect = document.querySelector('#mode-select');
const currentMode = document.querySelector('#current-mode');
const modeHint = document.querySelector('#mode-hint');
const newConversation = document.querySelector('#new-conversation');
const statusNames = { COMPLETE: '已完成', PARTIAL: '部分完成', FAILED: '失败', CANCELLED: '已取消', SUCCESS: '成功', ERROR: '失败', DENIED: '已拒绝', TIMEOUT: '已超时', SKIPPED: '已跳过', INCOMPLETE: '未完成' };
const phaseNames = { PLAN: '规划中', WORK: '执行工具', EXECUTE: '执行步骤', REPLAN: '检查下一步', REPAIR: '修复计划格式', SOLVE: '汇总回答', FINALIZE: '汇总回答' };
const modeNames = { REACT: 'ReAct', REWOO: 'ReWOO', PLAN_EXECUTE: 'Plan-and-Execute' };
let preferredMode = 'REACT';
try { preferredMode = localStorage.getItem('agent-mode') || preferredMode; } catch (_) { /* Storage may be disabled. */ }
let state = conversationState.create(preferredMode);
let sessionId;
let pending = false;
let statusTimer;
let conversationTurn = 0;

function showProtocolStatus(settings) {
  protocolSelect.value = settings.savedProtocol;
  const active = protocolNames[settings.activeProtocol];
  protocolStatus.textContent = settings.restartRequired
    ? `当前使用 ${active}。设置已保存，重启应用后生效；重启会清空当前会话。`
    : `当前使用 ${active}。修改后需要重启应用。`;
}

document.querySelector('#settings-open').addEventListener('click', async () => {
  settingsDialog.showModal();
  settingsSave.disabled = true;
  protocolStatus.textContent = '正在读取设置…';
  try {
    const response = await fetch('/api/settings');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    showProtocolStatus(await response.json());
    settingsSave.disabled = false;
  } catch (error) {
    protocolStatus.textContent = `无法读取设置：${error.message}`;
  }
});

document.querySelector('#settings-close').addEventListener('click', () => settingsDialog.close());
settingsForm.addEventListener('submit', async event => {
  event.preventDefault();
  settingsSave.disabled = true;
  try {
    const response = await fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ protocol: protocolSelect.value }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    showProtocolStatus(await response.json());
  } catch (error) {
    protocolStatus.textContent = `保存失败：${error.message}`;
  } finally {
    settingsSave.disabled = false;
  }
});

function setInputDisabled(disabled) {
  promptInput.disabled = disabled;
  sendButton.disabled = disabled;
  modelSelect.disabled = disabled;
  effortSelect.disabled = disabled;
  modeSelect.disabled = disabled;
  newConversation.disabled = pending || state.running;
  modeSelect.value = state.mode;
  modeSelect.hidden = state.modeLocked;
  document.querySelector('#mode-label').hidden = state.modeLocked;
  currentMode.hidden = !state.modeLocked;
  currentMode.textContent = `执行模式：${modeNames[state.mode]}`;
  modeHint.textContent = state.modeLocked ? '本对话模式已固定，新建对话可切换。' : '首条消息发送后固定，可新建对话切换。';
}

function scrollToLatest() {
  window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
}

function addMessage(role, content = '') {
  welcome.remove();
  const row = document.createElement('section');
  row.className = `message ${role}`;

  const avatar = document.createElement('div');
  avatar.className = 'avatar';
  avatar.textContent = role === 'user' ? '你' : 'A';

  const body = document.createElement('div');
  body.className = 'message-body';
  const label = document.createElement('div');
  label.className = 'message-label';
  label.textContent = role === 'user' ? '你' : 'Agent';
  const text = document.createElement('div');
  text.className = 'message-text';
  text.textContent = content;

  body.append(label, text);
  row.append(avatar, body);
  conversation.append(row);
  scrollToLatest();
  return { body, text };
}

function addNote(parent, content, isError = false) {
  const note = document.createElement('div');
  note.className = isError ? 'tool-note error-note' : 'tool-note';
  note.textContent = content;
  parent.append(note);
  scrollToLatest();
}

async function showApproval(parent, approval) {
  const approvalSession = sessionId;
  const card = document.createElement('div');
  card.className = 'approval';
  const title = document.createElement('div');
  title.className = 'approval-title';
  title.textContent = approval.kind === 'command'
    ? '允许执行以下 Bash 命令？'
    : '允许将以下命令输出发送给 DeepSeek？';
  const content = document.createElement('pre');
  content.textContent = approval.content;
  const actions = document.createElement('div');
  actions.className = 'approval-actions';
  const deny = document.createElement('button');
  deny.type = 'button';
  deny.textContent = '拒绝';
  const allow = document.createElement('button');
  allow.type = 'button';
  allow.className = 'primary';
  allow.textContent = '允许';
  const result = document.createElement('span');
  result.className = 'approval-result';
  actions.append(deny, allow, result);
  card.append(title, content, actions);
  parent.append(card);
  scrollToLatest();

  async function decide(approved) {
    deny.disabled = true;
    allow.disabled = true;
    try {
      const response = await fetch('/api/approve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Agent-Session': approvalSession },
        body: JSON.stringify({ id: approval.id, approved }),
      });
      if (!response.ok) throw new Error('确认已过期');
      result.textContent = approved ? '已允许' : '已拒绝';
    } catch (error) {
      result.textContent = error.message;
    }
  }
  deny.addEventListener('click', () => decide(false));
  allow.addEventListener('click', () => decide(true));
}

async function readEvents(response, onEvent) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffered = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffered += decoder.decode(value, { stream: true });
    const lines = buffered.split('\n');
    buffered = lines.pop();
    for (const line of lines) {
      if (line) onEvent(JSON.parse(line));
    }
  }
  buffered += decoder.decode();
  if (buffered.trim()) onEvent(JSON.parse(buffered));
}

async function sendPrompt(prompt, settings) {
  const turnNumber = ++conversationTurn;
  traceView.beginTurn(turnNumber);
  traceView.show('conversation');
  pending = true;
  const requestSession = sessionId;
  setInputDisabled(true);
  addMessage('user', prompt);
  const answer = addMessage('assistant');
  let finished = false;
  const plans = new Map();
  const phase = document.createElement('div');
  phase.className = 'phase-status';
  answer.body.prepend(phase);

  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Agent-Session': requestSession },
      body: JSON.stringify({ prompt, mode: state.mode, ...settings }),
    });
    if (!response.ok) {
      const error = await response.json();
      throw new Error(`${error.code || response.status}：${error.message || '请求失败'}`);
    }
    await readEvents(response, event => {
      if (requestSession !== sessionId) return;
      if (event.type === 'run_started') {
        state = conversationState.started(state, event.data);
        try { localStorage.setItem('agent-mode', state.mode); } catch (_) { /* Optional preference. */ }
        setInputDisabled(true);
      }
      if (state.activeRequestId && event.requestId !== state.activeRequestId) return;
      if (event.type === 'phase') phase.textContent = `${phaseNames[event.data.phase] || event.data.phase}${event.data.stepId ? ' · ' + event.data.stepId : ''} · 执行中`;
      if (event.type === 'plan') showPlan(answer.body, event.data, plans);
      if (event.type === 'evidence') {
        const row = plans.get(event.data.stepId || event.data.id);
        if (row) row.status.textContent = statusNames[event.data.result.status] || event.data.result.status;
      }
      if (event.type === 'step_result') {
        const row = plans.get(event.data.stepId);
        if (row) { row.status.textContent = statusNames[event.data.status] || event.data.status; row.detail.textContent += '\n结果：' + event.data.summary; }
      }
      if (event.type === 'decision') addNote(answer.body, `计划检查：${event.data.reason}`);
      if (event.type === 'run_result') {
        phase.textContent = `${statusNames[event.data.status] || event.data.status} · ${event.data.completionCheck === 'UNVERIFIED' ? '结果尚未做语义校验' : event.data.completionCheck}`;
        traceView.result(turnNumber, event.data);
      }
      if (event.type === 'text') answer.text.textContent += event.data;
      if (event.type === 'tool') addNote(answer.body, event.data);
      if (event.type === 'trace') traceView.record(turnNumber, event.data);
      if (event.type === 'approval') {
        traceView.show('conversation');
        showApproval(answer.body, event.data);
      }
      if (event.type === 'error') {
        const message = typeof event.data === 'string' ? event.data : `${event.data.code}：${event.data.message}`;
        addNote(answer.body, message, true);
        traceView.recordError(turnNumber, message);
        finished = true;
      }
      if (event.type === 'done') finished = true;
      scrollToLatest();
    });
    if (!finished) {
      const message = '连接中断，回答可能不完整。';
      addNote(answer.body, message, true);
      traceView.recordError(turnNumber, message);
    }
  } catch (error) {
    addNote(answer.body, error.message, true);
    traceView.recordError(turnNumber, error.message);
  } finally {
    traceView.finishTurn(turnNumber);
    pending = false;
    await refreshStatus();
    setInputDisabled(!sessionId || state.running);
    if (!state.running) promptInput.focus();
  }
}

form.addEventListener('submit', event => {
  event.preventDefault();
  const prompt = promptInput.value.trim();
  if (!sessionId || pending || state.running || !prompt) return;
  const settings = {
    model: modelSelect.value,
    reasoningLevel: effortSelect.value,
  };
  promptInput.value = '';
  sendPrompt(prompt, settings);
});

promptInput.addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    form.requestSubmit();
  }
});

function showPlan(parent, data, rows) {
  const activeIds = new Set(data.plan.steps.map(step => step.id));
  for (const [id, row] of rows) if (!activeIds.has(id) && row.status.textContent === '等待执行') row.status.textContent = '已被新计划替换';
  for (const old of parent.querySelectorAll('.plan-card')) old.dataset.historical = 'true';
  const card = document.createElement('details');
  card.className = 'plan-card';
  card.open = true;
  const heading = document.createElement('summary');
  heading.textContent = `${modeNames[data.mode]} · 计划 v${data.revision + 1}`;
  card.append(heading);
  for (const step of data.plan.steps) {
    const row = document.createElement('div');
    row.className = 'plan-step';
    const label = document.createElement('strong');
    label.textContent = `${step.id} · ${step.goal || step.purpose}`;
    const status = document.createElement('span');
    status.className = 'plan-status';
    // Successful retained steps keep their known state in the new revision.
    status.textContent = rows.get(step.id)?.status.textContent || '等待执行';
    const detail = document.createElement('pre');
    detail.textContent = step.tool ? `${step.tool} ${JSON.stringify(step.arguments)}\n依赖：${[...new Set((Object.values(step.arguments).join(' ').match(/#E[1-9][0-9]*/g) || []))].join(', ') || '无'}`
      : `验收：${step.successCriteria}\n最少证据：${step.minEvidence} · 工具：${step.allowedTools.join(', ') || '无'}\n依赖：${step.dependsOn.join(', ') || '无'}`;
    row.append(label, status, detail);
    card.append(row);
    rows.set(step.id, { status, detail });
  }
  parent.insertBefore(card, parent.querySelector('.message-text'));
}

async function refreshStatus() {
  clearTimeout(statusTimer);
  if (!sessionId) return;
  try {
    const response = await fetch('/api/session/status', { headers: { 'X-Agent-Session': sessionId } });
    if (response.status === 401) {
      sessionId = null;
      state = conversationState.create(state.mode);
      connectionStatus.textContent = '会话已失效，请新建对话';
      return;
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    state = conversationState.status(state, await response.json());
    connectionStatus.textContent = state.running ? '正在结束上一轮…' : '已连接';
  } catch (_) {
    // A lost response does not establish whether acceptance occurred.
    state = { ...state, running: true };
    connectionStatus.textContent = '连接状态未知，正在核对…';
  } finally {
    setInputDisabled(!sessionId || pending || state.running);
    if (state.running && !pending) statusTimer = setTimeout(refreshStatus, 1500);
  }
}

async function createConversation() {
  if (pending || state.running) return;
  pending = true;
  setInputDisabled(true);
  try {
    const response = await fetch('/api/session');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    sessionId = data.sessionId;
    state = conversationState.status(conversationState.create(state.mode), data);
    conversation.replaceChildren(welcome);
    traceView.reset();
    conversationTurn = 0;
    connectionStatus.textContent = '已连接';
    promptInput.value = '';
    traceView.show('conversation');
  } catch (error) {
    connectionStatus.textContent = '连接失败';
    addNote(conversation, `无法创建会话：${error.message}`, true);
  } finally {
    pending = false;
    setInputDisabled(!sessionId || state.running);
    if (sessionId) promptInput.focus();
  }
}
modeSelect.addEventListener('change', () => { state = conversationState.select(state, modeSelect.value); });
newConversation.addEventListener('click', createConversation);
createConversation();
