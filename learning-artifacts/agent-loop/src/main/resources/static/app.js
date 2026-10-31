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

let sessionId;
let running = false;
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
        headers: { 'Content-Type': 'application/json', 'X-Agent-Session': sessionId },
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
  running = true;
  setInputDisabled(true);
  addMessage('user', prompt);
  const answer = addMessage('assistant');
  let finished = false;

  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Agent-Session': sessionId },
      body: JSON.stringify({ prompt, ...settings }),
    });
    if (!response.ok) throw new Error(`请求失败：HTTP ${response.status}`);
    await readEvents(response, event => {
      if (event.type === 'text') answer.text.textContent += event.data;
      if (event.type === 'tool') addNote(answer.body, event.data);
      if (event.type === 'trace') traceView.record(turnNumber, event.data);
      if (event.type === 'approval') {
        traceView.show('conversation');
        showApproval(answer.body, event.data);
      }
      if (event.type === 'error') {
        addNote(answer.body, event.data, true);
        traceView.recordError(turnNumber, event.data);
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
    running = false;
    setInputDisabled(false);
    promptInput.focus();
  }
}

form.addEventListener('submit', event => {
  event.preventDefault();
  const prompt = promptInput.value.trim();
  if (!sessionId || running || !prompt) return;
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

fetch('/api/session')
  .then(async response => {
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  })
  .then(data => {
    sessionId = data.sessionId;
    connectionStatus.textContent = '已连接';
    setInputDisabled(false);
    promptInput.focus();
  })
  .catch(error => {
    connectionStatus.textContent = '连接失败';
    addNote(conversation, `无法创建会话：${error.message}`, true);
  });
