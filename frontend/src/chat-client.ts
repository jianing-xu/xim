import type { ChatState, ClientEvent, Conversation, Message, ServerEvent } from './protocol';

interface Pending {
  key: string;
  text: string;
  messageId?: string;
  timer: ReturnType<typeof setTimeout>;
}

const initialState = (): ChatState => ({
  connection: 'connecting', session: null, users: [], conversations: [], current: null,
  tab: 'conversations', showChat: false, loginPending: false, loginError: '', toast: '', members: null,
});

export function websocketUrl(): string {
  return import.meta.env.VITE_WS_URL || `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`;
}

function messageId(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID()
    : `${Date.now()}-${crypto.getRandomValues(new Uint32Array(2)).join('-')}`;
}

/** Owns transport and immutable snapshots. React subscribes without duplicating socket listeners. */
export class ChatClient {
  private state = initialState();
  private listeners = new Set<() => void>();
  private socket: WebSocket | null = null;
  private stopped = true;
  private retry = 0;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private loginTimer?: ReturnType<typeof setTimeout>;
  private heartbeat?: ReturnType<typeof setInterval>;
  private toastTimer?: ReturnType<typeof setTimeout>;
  private pending = new Map<string, Pending>();
  private creatingGroup = false;

  constructor(private readonly url: () => string = websocketUrl) {}

  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  private patch(fields: Partial<ChatState>) {
    this.state = { ...this.state, ...fields };
    this.listeners.forEach(listener => listener());
  }

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    this.connect();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    clearTimeout(this.loginTimer);
    clearTimeout(this.toastTimer);
    clearInterval(this.heartbeat);
    this.pending.forEach(entry => clearTimeout(entry.timer));
    this.pending.clear();
    const socket = this.socket;
    this.socket = null;
    socket?.close();
  }

  private connect() {
    if (this.stopped) return;
    this.patch({ connection: 'connecting' });
    const socket = new WebSocket(this.url());
    this.socket = socket;
    const active = () => !this.stopped && this.socket === socket;
    socket.addEventListener('open', () => {
      if (!active()) return;
      this.retry = 0;
      this.patch({ connection: 'online', loginPending: false });
      clearInterval(this.heartbeat);
      this.heartbeat = setInterval(() => {
        this.send({ type: 'heartbeat' });
        if (this.state.session) this.send({ type: 'users' });
      }, 5000);
    });
    socket.addEventListener('message', event => {
      if (!active()) return;
      try { this.receive(JSON.parse(event.data) as ServerEvent); }
      catch { this.notify('无法处理收到的消息，请刷新页面重试'); }
    });
    socket.addEventListener('close', () => {
      if (!active()) return;
      clearInterval(this.heartbeat);
      clearTimeout(this.loginTimer);
      this.pending.forEach((_, id) => this.finishPending(id, '连接中断，发送结果未知'));
      this.patch({ connection: 'offline', session: null, loginPending: false, members: null,
        loginError: this.state.loginError || '连接已断开。恢复后请重新登录。' });
      this.retryTimer = setTimeout(() => this.connect(), Math.min(1000 * 2 ** this.retry++, 10000));
    });
    socket.addEventListener('error', () => {
      if (active()) this.patch({ connection: 'offline' });
    });
  }

  send(event: ClientEvent): boolean {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      this.notify('连接已断开，请等待连接恢复');
      return false;
    }
    this.socket.send(JSON.stringify(event));
    return true;
  }

  notify(text: string) {
    clearTimeout(this.toastTimer);
    this.patch({ toast: text });
    this.toastTimer = setTimeout(() => this.patch({ toast: '' }), 5000);
  }

  login(username: string, password: string) {
    if (this.state.loginPending) return false;
    if (!this.send({ type: 'login', username: username.trim(), password })) return false;
    this.patch({ loginPending: true, loginError: '' });
    this.loginTimer = setTimeout(() => {
      this.patch({ loginError: '登录响应超时，正在重新连接。', loginPending: false });
      this.socket?.close();
    }, 8000);
    return true;
  }

  private resetChat() {
    this.pending.forEach(entry => clearTimeout(entry.timer));
    this.pending.clear();
    this.creatingGroup = false;
    this.patch({ users: [], conversations: [], current: null, tab: 'conversations', showChat: false, members: null });
  }

  private conversation(kind: Conversation['kind'], id: string, name?: string): Conversation {
    const key = `${kind}:${id}`;
    let chat = this.state.conversations.find(item => item.key === key);
    if (!chat) {
      chat = { key, kind, id, name: name || `群聊 ${id}`, messages: [], unread: 0, draft: '' };
      this.patch({ conversations: [...this.state.conversations, chat] });
    } else if (name && chat.name !== name) {
      this.updateChat(key, { name });
      chat = { ...chat, name };
    }
    return chat;
  }

  private updateChat(key: string, fields: Partial<Conversation>) {
    this.patch({ conversations: this.state.conversations.map(chat => chat.key === key ? { ...chat, ...fields } : chat) });
  }

  choose(kind: Conversation['kind'], id: string, name?: string) {
    const chat = this.conversation(kind, id, name);
    this.updateChat(chat.key, { unread: 0 });
    this.patch({ current: chat.key, tab: 'conversations', showChat: true });
  }

  switchTab(tab: ChatState['tab']) {
    this.patch({ tab, showChat: false });
    if (tab === 'users') this.send({ type: 'users' });
  }
  back() { this.patch({ showChat: false }); }
  closeMembers() { this.patch({ members: null }); }
  setDraft(text: string) { if (this.state.current) this.updateChat(this.state.current, { draft: text }); }

  isOnline(chat: Conversation) { return this.state.users.some(user => user.userId === chat.id); }

  private appendMessage(chat: Conversation, fields: Omit<Message, 'id' | 'time'>): Message {
    const message: Message = { id: messageId(), time: Date.now(), ...fields };
    this.updateChat(chat.key, { messages: [...chat.messages, message],
      unread: chat.unread + (this.state.current !== chat.key && !message.mine ? 1 : 0) });
    return message;
  }

  sendMessage() {
    const chat = this.state.conversations.find(item => item.key === this.state.current);
    const session = this.state.session;
    if (!chat || !session || !chat.draft.trim() || this.state.connection !== 'online'
        || (chat.kind === 'user' && !this.isOnline(chat))) return;
    const text = chat.draft;
    if (text.length > 4000) { this.notify('消息长度不能超过 4000'); return; }
    const requestId = messageId();
    if (!this.send(chat.kind === 'group'
      ? { type: 'groupMessage', groupId: chat.id, message: text, requestId }
      : { type: 'message', toUserId: chat.id, message: text, requestId })) return;
    // Group messages arrive through the broadcast, so do not append them twice.
    const message = chat.kind === 'user'
      ? this.appendMessage(chat, { mine: true, name: session.username, text, status: '发送中…' }) : undefined;
    this.pending.set(requestId, { key: chat.key, text, messageId: message?.id,
      timer: setTimeout(() => this.finishPending(requestId, '响应超时，发送结果未知，请检查会话后再重试'), 8000) });
    this.updateChat(chat.key, { draft: '' });
  }

  createGroup(userIds: string[]) {
    if (this.send({ type: 'createGroup', userIds })) { this.creatingGroup = true; return true; }
    return false;
  }

  private finishPending(id: string, error?: string) {
    const pending = this.pending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(id);
    const chat = this.state.conversations.find(item => item.key === pending.key);
    if (chat) this.updateChat(chat.key, {
      messages: chat.messages.map(message => message.id === pending.messageId
        ? { ...message, status: error || '已发送' } : message),
      draft: error && !chat.draft ? pending.text : chat.draft,
    });
    if (error) this.notify(error);
  }

  private receive(data: ServerEvent) {
    switch (data.type) {
      case 'login':
        clearTimeout(this.loginTimer);
        this.patch({ loginPending: false });
        if (!data.success) { this.patch({ loginError: '账号或密码错误，请重试。' }); return; }
        this.resetChat();
        this.patch({ session: { userId: data.userId, username: data.username }, loginError: '' });
        try { localStorage.setItem('xim.username', data.username); } catch { /* Storage is optional. */ }
        this.send({ type: 'users' });
        break;
      case 'logout':
        if (!data.success) { this.notify('退出登录失败'); return; }
        this.resetChat(); this.patch({ session: null, loginError: '' }); break;
      case 'users': this.patch({ users: data.users }); break;
      case 'message':
        this.appendMessage(this.conversation('user', data.fromUserId, data.fromUsername),
          { mine: false, name: data.fromUsername, text: data.message }); break;
      case 'groupMessage':
        this.appendMessage(this.conversation('group', data.fromGroupId),
          { mine: data.fromUserId === this.state.session?.userId, name: data.fromUser, text: data.message }); break;
      case 'createGroup':
      case 'joinGroup': {
        if (!data.success) {
          this.creatingGroup = false;
          this.notify(data.reason === 'Group does not exist' ? '群组不存在，请检查群号。' : data.reason || '无法加入群聊');
          return;
        }
        const chat = this.conversation('group', data.groupId);
        if (data.type === 'joinGroup' || this.creatingGroup || !this.state.current) this.choose('group', chat.id);
        this.creatingGroup = false;
        this.notify(data.type === 'createGroup' ? `已加入新群聊，群号 ${data.groupId}` : '已加入群聊');
        break;
      }
      case 'quitGroup': {
        if (!data.success) { this.notify(data.reason || '退出群聊失败'); return; }
        const key = `group:${data.groupId}`;
        this.patch({ conversations: this.state.conversations.filter(chat => chat.key !== key),
          ...(this.state.current === key ? { current: null, showChat: false } : {}) });
        this.notify('已退出群聊'); break;
      }
      case 'members': this.patch({ members: data.sessions.filter(member => member !== null) }); break;
      case 'sent': if (data.requestId) this.finishPending(data.requestId); break;
      case 'error':
        if (data.requestId && this.pending.has(data.requestId)) this.finishPending(data.requestId, data.reason);
        else if (!this.state.session) {
          clearTimeout(this.loginTimer); this.patch({ loginPending: false, loginError: data.reason });
        } else { this.creatingGroup = false; this.notify(data.reason); }
        break;
    }
  }
}
