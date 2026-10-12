'use strict';

const $ = (id) => document.getElementById(id);
const state = { socket: null, session: null, users: [], conversations: new Map(), current: null, tab: 'conversations', pending: new Map(), retry: 0, loginTimer: null, heartbeat: null, creatingGroup: false };
let toastTimer;
const clock = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' });

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function avatar(name, group = false) { return element('span', `avatar${group ? ' group' : ''}`, group ? '#' : name.slice(0, 1).toUpperCase()); }
function toast(text) {
  $('toast').textContent = text; $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 5000);
}
function connected() { return state.socket?.readyState === WebSocket.OPEN; }
function send(type, fields = {}) {
  if (!connected()) { toast('连接已断开，请等待连接恢复'); return false; }
  state.socket.send(JSON.stringify({ type, ...fields })); return true;
}
function setConnection(text, kind) { $('connection').textContent = text; $('connection').className = `connection ${kind || ''}`; }
function clearLoginWait() { clearTimeout(state.loginTimer); state.loginTimer = null; $('login-button').disabled = !connected(); $('login-button').textContent = '登录'; }
function connect() {
  setConnection('正在连接');
  const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`);
  state.socket = socket;
  socket.addEventListener('open', () => {
    state.retry = 0; setConnection('已连接', 'online'); clearLoginWait();
    clearInterval(state.heartbeat);
    state.heartbeat = setInterval(() => { send('heartbeat'); if (state.session) send('users'); }, 5000);
  });
  socket.addEventListener('message', (event) => {
    try { receive(JSON.parse(event.data)); } catch (error) { console.error('无法处理服务端消息', error); toast('无法处理收到的消息，请刷新页面重试'); }
  });
  socket.addEventListener('close', () => {
    clearInterval(state.heartbeat); clearLoginWait();
    for (const [id] of state.pending) finishPending(id, '连接中断，发送结果未知');
    if (state.session) {
      state.session = null;
      document.querySelectorAll('dialog[open]').forEach((dialog) => dialog.close());
      $('chat-view').hidden = true; $('login-view').hidden = false;
      $('password').value = ''; $('login-error').textContent = '连接已断开。恢复后请重新登录。';
    }
    $('login-button').disabled = true;
    setConnection('连接断开 · 正在重连', 'offline');
    setTimeout(connect, Math.min(1000 * 2 ** state.retry++, 10000));
  });
  socket.addEventListener('error', () => setConnection('暂时无法连接', 'offline'));
}
function conversation(kind, id, name) {
  const key = `${kind}:${id}`;
  if (!state.conversations.has(key)) state.conversations.set(key, { key, kind, id, name: name || `群聊 ${id}`, messages: [], unread: 0, draft: '' });
  const chat = state.conversations.get(key);
  if (name) chat.name = name;
  return chat;
}
function choose(chat) {
  const previous = state.conversations.get(state.current);
  if (previous) previous.draft = $('message').value;
  state.current = chat.key; chat.unread = 0; state.tab = 'conversations';
  $('empty-chat').hidden = true; $('active-chat').hidden = false;
  $('chat-view').classList.add('show-chat'); $('message').value = chat.draft;
  renderList(); renderHeader(); renderMessages(); updateComposer();
  $('messages').scrollTop = $('messages').scrollHeight;
  if (matchMedia('(min-width:681px)').matches) $('message').focus();
}
function online(chat) { return state.users.some((user) => user.userId === chat.id); }
function renderHeader() {
  const chat = state.conversations.get(state.current); if (!chat) return;
  $('chat-title').textContent = chat.name;
  $('chat-subtitle').textContent = chat.kind === 'group' ? `群号 ${chat.id}` : `${online(chat) ? '在线' : '离线'} · ${chat.id}`;
  $('chat-avatar').textContent = chat.kind === 'group' ? '#' : chat.name.slice(0, 1).toUpperCase();
  $('chat-avatar').className = `avatar${chat.kind === 'group' ? ' group' : ''}`;
  $('group-actions').hidden = chat.kind !== 'group';
}
function renderList() {
  const query = $('search').value.trim().toLowerCase();
  const list = $('list'); list.replaceChildren();
  $('conversations-tab').classList.toggle('active', state.tab === 'conversations');
  $('users-tab').classList.toggle('active', state.tab === 'users');
  $('conversations-tab').setAttribute('aria-selected', String(state.tab === 'conversations'));
  $('users-tab').setAttribute('aria-selected', String(state.tab === 'users'));
  list.setAttribute('aria-labelledby', state.tab === 'users' ? 'users-tab' : 'conversations-tab');
  const users = state.users.filter((user) => user.userId !== state.session?.userId);
  $('online-count').textContent = users.length;
  const entries = state.tab === 'users' ? users.map((user) => ({ kind: 'user', id: user.userId, name: user.username, key: `user:${user.userId}` })) : [...state.conversations.values()].reverse();
  for (const chat of entries.filter((item) => `${item.name} ${item.id}`.toLowerCase().includes(query))) {
    const button = element('button', `conversation${state.current === chat.key ? ' selected' : ''}`);
    const copy = element('span', 'conversation-copy'); copy.append(element('strong', '', chat.name));
    const last = chat.messages?.at(-1);
    copy.append(element('span', 'preview', state.tab === 'users' ? `在线 · ${chat.id}` : last?.text || (chat.kind === 'group' ? `群号 ${chat.id}` : '开始你们的对话')));
    button.append(avatar(chat.name, chat.kind === 'group'), copy);
    if (chat.unread) button.append(element('span', 'unread', chat.unread > 99 ? '99+' : chat.unread));
    else if (chat.kind === 'user' && online(chat)) button.append(element('span', 'presence'));
    button.addEventListener('click', () => choose(conversation(chat.kind, chat.id, chat.name)));
    list.append(button);
  }
  if (!list.children.length) list.append(element('p', 'list-empty', query ? '没有找到匹配的联系人或群组' : state.tab === 'users' ? '还没有其他伙伴在线。\n用另一个窗口登录，即可开始聊天。' : '还没有会话。\n去在线列表找一个伙伴吧。'));
}
function renderMessages() {
  const chat = state.conversations.get(state.current); if (!chat) return;
  const list = $('messages'); const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
  const oldTop = list.scrollTop; list.replaceChildren();
  if (!chat.messages.length) list.append(element('p', 'message-empty', '还没有消息，打个招呼吧。'));
  for (const message of chat.messages) {
    const row = element('div', `message-row${message.mine ? ' mine' : ''}`);
    const content = element('div', 'message-content');
    const meta = element('div', 'message-meta'); meta.append(element('span', '', message.mine ? '我' : message.name), element('time', '', clock.format(message.time)));
    content.append(meta, element('div', 'bubble', message.text));
    if (message.mine && message.status) content.append(element('div', `message-status${message.status === '已发送' || message.status === '发送中…' ? '' : ' failed'}`, message.status));
    row.append(avatar(message.name), content); list.append(row);
  }
  list.scrollTop = nearBottom ? list.scrollHeight : oldTop;
}
function appendMessage(chat, message) {
  const entry = { time: new Date(), ...message };
  chat.messages.push(entry);
  if (state.current !== chat.key && !message.mine) chat.unread++;
  renderList(); if (state.current === chat.key) renderMessages();
  return entry;
}
function finishPending(id, error) {
  const pending = state.pending.get(id); if (!pending) return;
  clearTimeout(pending.timer); state.pending.delete(id);
  if (pending.message) pending.message.status = error || '已发送';
  if (error) {
    const chat = state.conversations.get(pending.key);
    if (chat && !chat.draft) chat.draft = pending.text;
    if (state.current === pending.key && !$('message').value) $('message').value = pending.text;
    toast(error);
  }
  if (state.current === pending.key) renderMessages(); updateComposer();
}
function resetChat() {
  state.users = []; state.conversations.clear(); state.current = null; state.creatingGroup = false;
  state.pending.forEach((pending) => clearTimeout(pending.timer)); state.pending.clear();
  state.tab = 'conversations'; $('search').value = ''; $('message').value = '';
  $('active-chat').hidden = true; $('empty-chat').hidden = false; $('chat-view').classList.remove('show-chat'); renderList();
}
function receive(data) {
  switch (data.type) {
    case 'login':
      clearLoginWait();
      if (!data.success) { $('login-error').textContent = '账号或密码错误，请重试。'; $('password').focus(); return; }
      resetChat(); state.session = { userId: data.userId, username: data.username }; $('password').value = '';
      $('login-error').textContent = ''; $('login-view').hidden = true; $('chat-view').hidden = false;
      $('self-name').textContent = data.username; $('self-id').textContent = data.userId; $('self-avatar').textContent = data.username.slice(0, 1).toUpperCase();
      try { localStorage.setItem('xim.username', data.username); } catch { /* Storage is optional. */ }
      send('users'); break;
    case 'logout':
      state.session = null; resetChat(); $('chat-view').hidden = true; $('login-view').hidden = false; $('username').focus(); break;
    case 'users': state.users = data.users; renderList(); renderHeader(); updateComposer(); break;
    case 'message':
      appendMessage(conversation('user', data.fromUserId, data.fromUsername), { mine: false, name: data.fromUsername, text: data.message }); break;
    case 'groupMessage':
      appendMessage(conversation('group', data.fromGroupId), { mine: data.fromUserId === state.session?.userId, name: data.fromUser, text: data.message }); break;
    case 'createGroup':
    case 'joinGroup':
      $('group-dialog').close(); $('join-dialog').close();
      if (!data.success) { toast(data.reason === 'Group does not exist' ? '群组不存在，请检查群号。' : data.reason || '无法加入群聊'); return; }
      {
        const chat = conversation('group', data.groupId);
        if (data.type === 'joinGroup' || state.creatingGroup || !state.current) choose(chat);
        else renderList();
        toast(data.type === 'createGroup' ? `已加入新群聊，群号 ${data.groupId}` : '已加入群聊');
        state.creatingGroup = false;
      }
      break;
    case 'quitGroup': {
      if (!data.success) { toast(data.reason || '退出群聊失败'); return; }
      const key = `group:${data.groupId}`; state.conversations.delete(key);
      if (state.current === key) { state.current = null; $('active-chat').hidden = true; $('empty-chat').hidden = false; $('chat-view').classList.remove('show-chat'); }
      renderList(); toast('已退出群聊'); break;
    }
    case 'members':
      $('member-list').replaceChildren();
      for (const member of data.sessions.filter(Boolean)) { const row = element('div', 'member'); row.append(avatar(member.username), element('strong', '', member.username), element('span', 'mono', member.userId)); $('member-list').append(row); }
      $('members-dialog').showModal(); break;
    case 'sent': finishPending(data.requestId); break;
    case 'error':
      if (state.pending.has(data.requestId)) finishPending(data.requestId, data.reason);
      else if (!state.session) { clearLoginWait(); $('login-error').textContent = data.reason; }
      else { state.creatingGroup = false; toast(data.reason); } break;
  }
}
function updateComposer() {
  const chat = state.conversations.get(state.current);
  const canSend = connected() && state.session && chat && (chat.kind === 'group' || online(chat));
  $('send').disabled = !canSend || !$('message').value.trim();
  $('message').disabled = !connected() || !state.session;
  $('compose-hint').textContent = chat?.kind === 'user' && !online(chat) ? '对方已离线，暂时无法发送' : 'Enter 发送 · Shift + Enter 换行';
}
$('login-form').addEventListener('submit', (event) => {
  event.preventDefault(); if (state.loginTimer || !connected()) return;
  $('login-error').textContent = ''; $('login-button').disabled = true; $('login-button').textContent = '正在登录…';
  send('login', { username: $('username').value.trim(), password: $('password').value });
  state.loginTimer = setTimeout(() => { $('login-error').textContent = '登录响应超时，正在重新连接。'; state.socket.close(); }, 8000);
});
$('message-form').addEventListener('submit', (event) => {
  event.preventDefault(); const chat = state.conversations.get(state.current); const text = $('message').value;
  if (!chat || !text.trim() || $('send').disabled) return;
  const requestId = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${crypto.getRandomValues(new Uint32Array(2)).join('-')}`;
  if (!send(chat.kind === 'group' ? 'groupMessage' : 'message', { requestId, message: text, ...(chat.kind === 'group' ? { groupId: chat.id } : { toUserId: chat.id }) })) return;
  let message;
  if (chat.kind === 'user') message = appendMessage(chat, { mine: true, name: state.session.username, text, status: '发送中…' });
  const timer = setTimeout(() => finishPending(requestId, '响应超时，发送结果未知，请检查会话后再重试'), 8000);
  state.pending.set(requestId, { key: chat.key, text, message, timer });
  chat.draft = ''; $('message').value = ''; updateComposer(); $('message').focus(); $('messages').scrollTop = $('messages').scrollHeight;
});
$('message').addEventListener('input', () => { const chat = state.conversations.get(state.current); if (chat) chat.draft = $('message').value; updateComposer(); });
$('message').addEventListener('keydown', (event) => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); $('message-form').requestSubmit(); } });
$('search').addEventListener('input', renderList);
function switchTab(tab) { state.tab = tab; renderList(); }
$('conversations-tab').addEventListener('click', () => switchTab('conversations'));
$('users-tab').addEventListener('click', () => { switchTab('users'); send('users'); });
$('browse-users').addEventListener('click', () => { switchTab('users'); $('chat-view').classList.remove('show-chat'); send('users'); });
$('back').addEventListener('click', () => $('chat-view').classList.remove('show-chat'));
$('logout').addEventListener('click', () => send('logout'));
$('new-group').addEventListener('click', () => {
  $('group-options').replaceChildren();
  for (const user of state.users.filter((user) => user.userId !== state.session?.userId)) {
    const label = element('label', 'group-choice'); const input = element('input'); input.type = 'checkbox'; input.value = user.userId; input.name = 'member';
    label.append(input, avatar(user.username), element('span', '', `${user.username} · ${user.userId}`)); $('group-options').append(label);
  }
  if (!$('group-options').children.length) $('group-options').append(element('p', 'muted', '暂时没有其他伙伴在线。可以先创建群聊，再分享群号。'));
  $('group-dialog').showModal();
});
$('group-form').addEventListener('submit', (event) => { event.preventDefault(); if (send('createGroup', { userIds: [...document.querySelectorAll('#group-options input:checked')].map((input) => input.value) })) { state.creatingGroup = true; $('group-dialog').close(); } });
$('join-group').addEventListener('click', () => { $('group-id').value = ''; $('join-dialog').showModal(); });
$('join-form').addEventListener('submit', (event) => { event.preventDefault(); if (send('joinGroup', { groupId: $('group-id').value.trim() })) $('join-dialog').close(); });
$('members').addEventListener('click', () => { const chat = state.conversations.get(state.current); if (chat) send('members', { groupId: chat.id }); });
let leavingGroup;
$('leave-group').addEventListener('click', () => { leavingGroup = state.conversations.get(state.current)?.id; $('leave-dialog').showModal(); });
$('leave-form').addEventListener('submit', (event) => { event.preventDefault(); if (send('quitGroup', { groupId: leavingGroup })) $('leave-dialog').close(); });
document.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', () => $(button.dataset.close).close()));
try { $('username').value = localStorage.getItem('xim.username') || ''; } catch { /* Storage is optional. */ }
connect();
