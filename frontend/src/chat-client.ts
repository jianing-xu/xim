import type { ChatState, ClientEvent, Conversation, ConversationSummary, Message, ServerEvent } from './protocol';

interface Pending {
  key: string;
  event: Extract<ClientEvent, { type: 'message' | 'groupMessage' }>;
  phase: 'sending' | 'unknown' | 'failed' | 'sent';
  timer?: ReturnType<typeof setTimeout>;
}

const initialState = (): ChatState => ({
  restoring: false, syncing: false, syncCursor: 0, connection: 'connecting', session: null, users: [], conversations: [], current: null,
  tab: 'conversations', showChat: false, loginPending: false, loginError: '', accountPending: false, accountError: '', accountNotice: '', toast: '', members: null,
  directory: { query: '', users: [], loading: false, loaded: false, error: '', nextAfter: null, hasMore: false },
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
  private accountRequest?: { type: 'register' | 'changePassword'; id: string; timer: ReturnType<typeof setTimeout> };
  private heartbeat?: ReturnType<typeof setInterval>;
  private toastTimer?: ReturnType<typeof setTimeout>;
  private pending = new Map<string, Pending>();
  private creatingGroup = false;
  private contactSearchTimer?: ReturnType<typeof setTimeout>;
  private contactRequest?: { id: string; append: boolean; timer: ReturnType<typeof setTimeout> };
  private contactRequestIds = new Set<string>();
  private contactAppend = false;
  private chatOwner: string | null = null;
  private historyPending = new Map<string, { key: string; timer: ReturnType<typeof setTimeout> }>();

  private readPending = new Map<string, { key: string; sequence: number; timer: ReturnType<typeof setTimeout> }>();

  private clearReads() {
    this.readPending.forEach(entry => clearTimeout(entry.timer)); this.readPending.clear();
  }

  private restoreBlocked = false;
  private logoutPending = false;
  private logoutRequest = false;
  private logoutRetry?: ReturnType<typeof setTimeout>;
  private connectionId?: string;
  private lastResponse = 0;
  private connectTimer?: ReturnType<typeof setTimeout>;
  private snapshotTimer?: ReturnType<typeof setTimeout>;
  private credentialRequest?: AbortController;
  private syncCursors = new Map<string, number>();
  private syncPending = new Map<string, { key: string; after: number; timer: ReturnType<typeof setTimeout> }>();
  private syncQueue: string[] = [];
  private syncTarget = 0;
  private recoverySnapshot = false;

  constructor(private readonly url: () => string = websocketUrl) {
    try {
      const cached = JSON.parse(sessionStorage.getItem('xim.recovery') || 'null');
      this.logoutPending = localStorage.getItem('xim.logoutPending') === 'true';
      this.restoreBlocked = sessionStorage.getItem('xim.restoreBlocked') === 'true' || this.logoutPending;
      if (this.restoreBlocked) this.state.loginError = sessionStorage.getItem('xim.restoreReason') || '';
      if (cached && typeof cached.owner === 'string' && Array.isArray(cached.conversations)) {
        this.chatOwner = cached.owner;
        this.connectionId = typeof cached.connectionId === 'string' ? cached.connectionId : undefined;
        this.state = { ...this.state, conversations: cached.conversations.map((chat: Conversation) => ({ ...chat, historyLoading: false })),
          current: cached.current, showChat: Boolean(cached.showChat) };
        this.syncCursors = new Map(cached.cursors || []);
        for (const [id, entry] of cached.pending || []) this.pending.set(id,{ ...entry, phase: entry.phase === 'failed' ? 'failed' : 'unknown' });
      }
    } catch { /* Optional per-tab cache; authorization always comes from the server. */ }
  }

  private persist() {
    try {
      if (!this.chatOwner) { sessionStorage.removeItem('xim.recovery'); return; }
      sessionStorage.setItem('xim.recovery', JSON.stringify({ owner: this.chatOwner, connectionId: this.connectionId,
        current: this.state.current, showChat: this.state.showChat, cursors: [...this.syncCursors],
        conversations: this.state.conversations.map(chat => ({ ...chat, messages: chat.messages.filter((message,index) => index >= chat.messages.length-200 || message.retryable || message.status === '发送中…'), historyLoading: false })),
        pending: [...this.pending].filter(([, entry]) => entry.phase !== 'sent').map(([id, entry]) => [id,{ key: entry.key, event: entry.event, phase: entry.phase === 'failed' ? 'failed' : 'unknown' }]) }));
    } catch { /* Cache quota or private browsing must not prevent chatting. */ }
  }

  private blockRestore(blocked: boolean) {
    this.restoreBlocked = blocked;
    try { sessionStorage.setItem('xim.restoreBlocked', String(blocked)); } catch { /* Optional. */ }
  }

  private clearSync() {
    clearTimeout(this.snapshotTimer);
    this.syncPending.forEach(entry => clearTimeout(entry.timer)); this.syncPending.clear(); this.syncQueue = [];
    this.patch({ syncing: false });
  }

  private sessionEndpoint() { return this.url().replace(/^ws/, 'http').replace(/\/ws(?:\?.*)?$/, '/api/session'); }

  private saveCredential(ticket: string, socket: WebSocket | null) {
    this.credentialRequest?.abort();
    const request = new AbortController(); this.credentialRequest = request;
    void fetch(this.sessionEndpoint(), { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ticket }), signal: AbortSignal.any([request.signal, AbortSignal.timeout(8000)]) }).then(response => {
        if (!response.ok) throw new Error('Credential exchange failed');
        if (!request.signal.aborted && this.socket === socket) {
          try { localStorage.removeItem('xim.logoutPending'); } catch { /* Optional. */ }
        }
      }).catch(() => {
        if (!request.signal.aborted && this.socket === socket && !this.stopped) {
          this.blockRestore(true); this.notify('登录凭证未保存，刷新后需要重新登录');
        }
      });
  }

  private invalidate(reason: string) {
    this.blockRestore(true); this.credentialRequest?.abort(); this.clearSync(); this.resetChat();
    try { sessionStorage.setItem('xim.restoreReason',reason); } catch { /* Optional. */ }
    clearTimeout(this.loginTimer);
    this.patch({ session: null, restoring: false, loginPending: false, loginError: reason });
  }

  private flushLogout() {
    try { this.logoutPending ||= localStorage.getItem('xim.logoutPending') === 'true'; } catch { /* In-memory fallback. */ }
    if (!this.logoutPending || this.logoutRequest) return;
    this.logoutRequest = true;
    this.patch({ loginPending: true });
    void fetch(this.sessionEndpoint(), { method: 'DELETE', credentials: 'include', signal: AbortSignal.timeout(8000) }).then(response => {
      if (!response.ok) throw new Error('Logout failed');
      this.logoutPending = false;
      try { localStorage.removeItem('xim.logoutPending'); } catch { /* Optional. */ }
    }).catch(() => {
      if (!this.stopped) this.notify('服务暂不可达，本窗口已退出；服务端注销将在连接恢复后重试');
    }).finally(() => {
      this.logoutRequest = false;
      if (!this.stopped) {
        this.patch({ loginPending: false });
        if (this.logoutPending && this.state.connection === 'online') {
          clearTimeout(this.logoutRetry);
          this.logoutRetry = setTimeout(() => this.flushLogout(),5000);
        }
      }
    });
  }

  logout() {
    this.blockRestore(true); this.credentialRequest?.abort();
    this.logoutPending = true;
    try { localStorage.setItem('xim.logoutPending','true'); } catch { /* Optional. */ }
    this.send({ type: 'logout' }); this.flushLogout();
    this.invalidate('已退出登录');
    if (this.socket) this.disconnect(this.socket);
  }

  revokeSessions() { return this.send({ type: 'revokeSessions' }); }


  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  private patch(fields: Partial<ChatState>) {
    this.state = { ...this.state, ...fields };
    this.persist();
    this.listeners.forEach(listener => listener());
  }

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    this.connect();
  }

  stop() {
    this.stopped = true;
    this.cancelContacts('连接已断开，请恢复后重试');
    clearTimeout(this.logoutRetry);
    this.credentialRequest?.abort();
    this.clearSync();
    clearTimeout(this.connectTimer);
    this.clearReads();
    clearTimeout(this.retryTimer);
    clearTimeout(this.loginTimer);
    this.finishAccount();
    clearTimeout(this.toastTimer);
    clearInterval(this.heartbeat);
    this.pending.forEach((entry, id) => {
      clearTimeout(entry.timer);
      if (entry.phase === 'sending') this.finishPending(id, '连接中断，发送结果未知', undefined, 'unknown');
    });
    this.historyPending.forEach((_, id) => this.finishHistory(id));
    this.patch({ connection: 'offline', restoring: false, loginPending: false });
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
    clearTimeout(this.connectTimer);
    this.connectTimer = setTimeout(() => { if (active()) this.disconnect(socket); }, 10000);
    socket.addEventListener('open', () => {
      if (!active()) return;
      clearTimeout(this.connectTimer);
      this.lastResponse = Date.now();
      this.patch({ connection: 'online', restoring: !this.restoreBlocked, loginPending: !this.restoreBlocked });
      this.flushLogout();
      if (!this.restoreBlocked) {
        this.send({ type: 'resume', ...(this.connectionId ? { connectionId: this.connectionId } : {}) });
        this.loginTimer = setTimeout(() => { if (active()) this.disconnect(socket); }, 8000);
      }
      clearInterval(this.heartbeat);
      this.heartbeat = setInterval(() => {
        if (Date.now() - this.lastResponse >= 15000) { this.disconnect(socket); return; }
        this.send({ type: 'heartbeat' });
        if (this.state.session && !this.state.restoring && !this.state.syncing) { this.send({ type: 'users' }); this.send({ type: 'conversations' }); }
      }, 5000);
    });
    socket.addEventListener('message', event => {
      if (!active()) return;
      this.lastResponse = Date.now();
      try { this.receive(JSON.parse(event.data) as ServerEvent); }
      catch { this.notify('无法处理收到的消息，请刷新页面重试'); }
    });
    socket.addEventListener('close', () => {
      if (active()) this.disconnected(socket);
    });
    socket.addEventListener('error', () => {
      if (active()) this.patch({ connection: 'offline' });
    });
  }

  private disconnect(socket: WebSocket) {
    if (this.socket !== socket || this.stopped) return;
    this.disconnected(socket); socket.close();
  }

  private disconnected(socket: WebSocket) {
    if (this.socket !== socket || this.stopped) return;
    this.socket = null;
    this.cancelContacts('连接已断开，请恢复后重试');
    clearTimeout(this.logoutRetry);
    clearInterval(this.heartbeat); clearTimeout(this.connectTimer); clearTimeout(this.loginTimer);
    this.clearSync(); this.clearReads();
    if (this.accountRequest) this.finishAccount('连接中断，操作结果未确认，请重新登录检查');
    this.pending.forEach((entry, id) => {
      if (entry.phase === 'sending') this.finishPending(id, '连接中断，发送结果未知', undefined, 'unknown');
    });
    this.historyPending.forEach((_, id) => this.finishHistory(id));
    this.patch({ connection: 'offline', restoring: false, loginPending: false, members: null });
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.connect(), Math.min(1000 * 2 ** this.retry++, 10000));
  }

  send(event: ClientEvent): boolean {
    if (this.state.restoring && !['resume','heartbeat'].includes(event.type)) return false;
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
    if (this.state.loginPending || this.state.accountPending || this.logoutPending || this.logoutRequest) return false;
    this.blockRestore(false);
    if (!this.send({ type: 'login', username: username.trim(), password })) return false;
    this.patch({ loginPending: true, loginError: '', accountError: '', accountNotice: '' });
    this.loginTimer = setTimeout(() => {
      this.patch({ loginError: '登录响应超时，正在重新连接。', loginPending: false });
      this.socket?.close();
    }, 8000);
    return true;
  }

  register(username: string, password: string, displayName?: string) {
    if (this.state.session) return false;
    return this.accountAction({ type: 'register', username: username.trim(), password,
      ...(displayName?.trim() ? { displayName: displayName.trim() } : {}), requestId: messageId() });
  }

  changePassword(currentPassword: string, newPassword: string) {
    if (!this.state.session) return false;
    return this.accountAction({ type: 'changePassword', currentPassword, newPassword, requestId: messageId() });
  }

  clearAccountFeedback() { this.patch({ accountError: '', accountNotice: '', loginError: '' }); }

  private accountAction(event: Extract<ClientEvent, { type: 'register' | 'changePassword' }>) {
    if (this.state.accountPending || this.state.loginPending || !this.send(event)) return false;
    this.patch({ accountPending: true, accountError: '', accountNotice: '', loginError: '' });
    // Retain only the operation and correlation ID; credentials never enter snapshots or retry queues.
    this.accountRequest = { type: event.type, id: event.requestId, timer: setTimeout(() => {
      this.finishAccount('操作响应超时，结果未确认，请重新登录检查');
      this.socket?.close();
    }, 8000) };
    return true;
  }

  private finishAccount(error = '') {
    clearTimeout(this.accountRequest?.timer);
    this.accountRequest = undefined;
    this.patch({ accountPending: false, accountError: error });
  }

  private resetChat() {
    this.cancelContacts();
    this.clearReads();
    this.pending.forEach(entry => clearTimeout(entry.timer));
    this.pending.clear();
    this.historyPending.forEach(entry => clearTimeout(entry.timer));
    this.historyPending.clear();
    this.creatingGroup = false;
    this.syncCursors.clear(); this.connectionId = undefined;
    this.chatOwner = null;
    this.patch({ syncCursor: 0, users: [], directory: initialState().directory, conversations: [], current: null, tab: 'conversations', showChat: false, members: null });
  }

  private conversation(kind: Conversation['kind'], id: string, name?: string): Conversation {
    const key = `${kind}:${id}`;
    let chat = this.state.conversations.find(item => item.key === key);
    if (!chat) {
      chat = { key, kind, id, name: name || `群聊 ${id}`, messages: [], unread: 0, draft: '', lastSequence: 0, readSequence: 0 };
      this.patch({ conversations: [...this.state.conversations, chat] });
    } else if (name && chat.name !== name) {
      this.updateChat(key, { name });
      chat = { ...chat, name };
    }
    return chat;
  }

  private updateChat(key: string, fields: Partial<Conversation>) {
    this.patch({ conversations: this.state.conversations.map(chat => chat.key === key ? { ...chat, ...fields } : chat)
      .sort((a, b) => (b.lastSequence ?? 0) - (a.lastSequence ?? 0) || a.key.localeCompare(b.key)) });
  }

  choose(kind: Conversation['kind'], id: string, name?: string) {
    const chat = this.conversation(kind, id, name);
    this.patch({ current: chat.key, tab: 'conversations', showChat: true });
    this.loadHistory(chat.key, true);
  }

  loadHistory(key: string, latest = false) {
    const chat = this.state.conversations.find(item => item.key === key);
    if (!chat || !this.state.session || this.state.restoring || this.state.syncing || chat.historyLoading || (!latest && chat.historyLoaded && !chat.hasMore)) return;
    const requestId = messageId();
    if (!this.send({ type: 'history', kind: chat.kind, id: chat.id, limit: 50, requestId,
      ...(!latest && chat.historyLoaded && chat.nextBefore ? { before: chat.nextBefore } : {}) })) return;
    this.updateChat(key, { historyLoading: true });
    this.historyPending.set(requestId, { key, timer: setTimeout(() => {
      this.finishHistory(requestId); this.notify('历史查询超时，请重试');
    }, 8000) });
  }

  private finishHistory(id: string) {
    const pending = this.historyPending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer); this.historyPending.delete(id);
    this.updateChat(pending.key, { historyLoading: false });
  }

  /** Called after rendering: a selected key alone never means its messages were read. */
  reportReading(key: string, visible: boolean, atBottom: boolean) {
    const chat = this.state.conversations.find(item => item.key === key);
    if (!chat || !visible || !atBottom || this.state.connection !== 'online' || this.state.restoring || this.state.syncing || !this.state.session || !this.state.showChat
      || this.state.tab !== 'conversations' || this.state.current !== key
      || typeof document !== 'undefined' && document.visibilityState !== 'visible'
      || !chat.historyLoaded || chat.historyLoading) return;
    const sequence = Math.max(0, ...chat.messages.map(message => message.sequence ?? 0));
    if (!sequence || sequence < (chat.lastSequence ?? 0) || sequence <= (chat.readSequence ?? 0)
      || [...this.readPending.values()].some(entry => entry.key === key)) return;
    const requestId = messageId();
    if (!this.send({ type: 'read', kind: chat.kind, id: chat.id, sequence: String(sequence), requestId })) return;
    this.readPending.set(requestId, { key, sequence, timer: setTimeout(() => {
      this.readPending.delete(requestId); this.notify('阅读位置同步超时，将在下次同步时重试');
    }, 8000) });
  }

  private applySummary(summary: ConversationSummary, cursor: number) {
    const chat = this.conversation(summary.kind, summary.id, summary.name);
    const readSequence = summary.readSequence ?? chat.readSequence ?? 0;
    // An older baseline must not undo a newer snapshot or confirmed reading position.
    if (cursor < (chat.summaryCursor ?? 0) && readSequence <= (chat.readSequence ?? 0)
      || readSequence < (chat.readSequence ?? 0)) {
      this.send({ type: 'conversations' }); return;
    }
    const newer = (chat.lastSequence ?? 0) > cursor;
    const increments = new Set(chat.messages.filter(message => !message.mine && (message.sequence ?? 0) > cursor
      && (message.sequence ?? 0) > readSequence).map(message => message.sequence)).size;
    this.updateChat(chat.key, { name: summary.name, summaryCursor: Math.max(cursor, chat.summaryCursor ?? 0), readSequence,
      unread: summary.unread === undefined ? chat.unread : summary.unread + increments,
      ...(!newer ? { lastSequence: summary.lastSequence ?? chat.lastSequence,
        lastTime: summary.lastTime ?? chat.lastTime, lastMessage: summary.lastMessage ?? chat.lastMessage } : {}) });
    // Known deltas give an immediate count; a fresh server snapshot also covers pushes lost in transit.
    if (newer) this.send({ type: 'conversations' });
    if (!this.state.syncing && !this.state.restoring && this.state.current === chat.key && this.state.showChat && chat.historyLoaded && !chat.historyLoading
      && (summary.lastSequence ?? 0) > Math.max(0, ...chat.messages.map(message => message.sequence ?? 0)))
      this.loadHistory(chat.key, true);
  }

  private committedFields(chat: Conversation, message: Message): Partial<Conversation> {
    if (!message.sequence || message.sequence <= (chat.lastSequence ?? 0)) return {};
    return { lastSequence: message.sequence, lastTime: message.time,
      lastMessage: { id: message.id, sequence: message.sequence, time: message.time,
        fromUserId: message.fromUserId ?? (message.mine ? this.state.session!.userId : chat.kind === 'user' ? chat.id : ''),
        fromUsername: message.name, message: message.text },
      unread: chat.unread + (!message.mine && message.sequence > (chat.readSequence ?? 0) ? 1 : 0) };
  }

  switchTab(tab: ChatState['tab']) {
    this.patch({ tab, showChat: false });
    if (tab === 'users') { this.send({ type: 'users' }); this.browseContacts(); }
  }
  private patchDirectory(fields: Partial<ChatState['directory']>) {
    this.patch({ directory: { ...this.state.directory, ...fields } });
  }

  private cancelContacts(error = '') {
    clearTimeout(this.contactSearchTimer); this.contactSearchTimer = undefined;
    clearTimeout(this.contactRequest?.timer); this.contactRequest = undefined;
    if (this.state.directory.loading) this.patchDirectory({ loading: false, error });
  }

  browseContacts() {
    if (!this.state.directory.loading) this.queryContacts(false);
  }

  searchContacts(query: string) {
    this.cancelContacts(); this.contactAppend = false;
    this.patchDirectory({ query, users: [], loading: true, loaded: false, error: '', hasMore: false, nextAfter: null });
    this.contactSearchTimer = setTimeout(() => { this.contactSearchTimer = undefined; this.queryContacts(false); }, 250);
  }

  loadMoreContacts() {
    if (this.state.directory.hasMore && this.state.directory.nextAfter && !this.state.directory.loading) this.queryContacts(true);
  }

  retryContacts() { if (!this.state.directory.loading) this.queryContacts(this.contactAppend); }

  private queryContacts(append: boolean) {
    this.cancelContacts(); this.contactAppend = append;
    if (!this.state.session || this.state.connection !== 'online' || this.state.restoring) {
      this.patchDirectory({ loading: false, error: '连接未恢复，请稍后重试' }); return;
    }
    const requestId = messageId();
    const { query, nextAfter } = this.state.directory;
    if (!this.send({ type: 'contacts', query, limit: 20, requestId, ...(append && nextAfter ? { after: nextAfter } : {}) })) {
      this.patchDirectory({ loading: false, error: '联系人查询未发送，请重试' }); return;
    }
    this.contactRequestIds.add(requestId);
    if (this.contactRequestIds.size > 100) this.contactRequestIds.delete(this.contactRequestIds.values().next().value!);
    this.patchDirectory({ loading: true, error: '' });
    this.contactRequest = { id: requestId, append, timer: setTimeout(() => {
      this.contactRequest = undefined;
      this.patchDirectory({ loading: false, error: '联系人查询超时，请重试' });
    }, 8000) };
  }
  back() { this.patch({ showChat: false }); }
  closeMembers() { this.patch({ members: null }); }
  setDraft(text: string) { if (this.state.current) this.updateChat(this.state.current, { draft: text }); }

  isOnline(chat: Pick<Conversation, 'id'>) {
    return this.state.connection === 'online' && !this.state.restoring
      && (this.state.directory.users.find(user => user.userId === chat.id)?.online ?? this.state.users.some(user => user.userId === chat.id));
  }

  private appendMessage(chat: Conversation, fields: Omit<Message, 'id' | 'time'> & Partial<Pick<Message, 'id' | 'time'>>): Message {
    const message: Message = { id: messageId(), time: Date.now(), ...fields };
    if (chat.messages.some(item => item.id === message.id)) return message;
    this.updateChat(chat.key, { messages: [...chat.messages, message],
      ...this.committedFields(chat, message),
      ...(!message.sequence ? { unread: chat.unread + (!message.mine ? 1 : 0) } : {}) });
    return message;
  }

  sendMessage() {
    const chat = this.state.conversations.find(item => item.key === this.state.current);
    const session = this.state.session;
    if (!chat || !session || this.state.restoring || this.state.syncing || !chat.draft.trim() || this.state.connection !== 'online') return;
    const text = chat.draft;
    if (text.length > 4000) { this.notify('消息长度不能超过 4000'); return; }
    const requestId = messageId();
    const event: Pending['event'] = chat.kind === 'group'
      ? { type: 'groupMessage', groupId: chat.id, message: text, requestId }
      : { type: 'message', toUserId: chat.id, message: text, requestId };
    if (!this.send(event)) return;
    this.appendMessage(chat, { id: requestId, clientMessageId: requestId, mine: true,
      name: session.username, text, status: '发送中…', retryable: false });
    const entry: Pending = { key: chat.key, event, phase: 'sending' };
    this.pending.set(requestId, entry);
    this.armPending(requestId, entry);
    this.updateChat(chat.key, { draft: '' });
  }

  private armPending(id: string, entry: Pending) {
    clearTimeout(entry.timer);
    entry.timer = setTimeout(() => this.finishPending(id, '响应超时，发送结果未知，可重试', undefined, 'unknown'), 8000);
  }

  retryMessage(id: string) {
    const entry = this.pending.get(id);
    if (!entry || entry.phase === 'sent' || entry.phase === 'sending' || !this.state.session
      || this.state.session.userId !== this.chatOwner || this.state.restoring || this.state.syncing || this.state.connection !== 'online') return;
    if (!this.send(entry.event)) return;
    entry.phase = 'sending';
    const chat = this.state.conversations.find(item => item.key === entry.key);
    if (chat) this.updateChat(chat.key, { messages: chat.messages.map(message => message.mine && message.clientMessageId === id
      ? { ...message, status: '发送中…', retryable: false } : message) });
    this.armPending(id, entry);
  }

  createGroup(userIds: string[]) {
    if (this.send({ type: 'createGroup', userIds })) { this.creatingGroup = true; return true; }
    return false;
  }

  private finishPending(id: string, error?: string, saved?: { id?: string; time?: number; sequence?: number }, phase: Pending['phase'] = 'failed') {
    const pending = this.pending.get(id);
    if (!pending || (error && pending.phase === 'sent')) return;
    clearTimeout(pending.timer);
    pending.timer = undefined;
    pending.phase = error ? phase : 'sent';
    const chat = this.state.conversations.find(item => item.key === pending.key);
    if (chat) {
      const own = chat.messages.find(message => message.mine && message.clientMessageId === id);
      if (own) {
        const corrected = { ...own, ...(saved?.id ? { id: saved.id, time: saved.time ?? own.time, sequence: saved.sequence ?? own.sequence } : {}),
          status: error || '已发送', retryable: Boolean(error) };
        // History or broadcast may already contain the committed row. Keep only the corrected local row.
        this.updateChat(chat.key, { ...this.committedFields(chat, corrected), messages: chat.messages
          .filter(message => message === own || message.id !== corrected.id)
          .map(message => message === own ? corrected : message).sort((a, b) => a.sequence && b.sequence ? a.sequence - b.sequence : a.time - b.time) });
      }
    }
    if (error && !this.stopped) this.notify(error);
  }

  private nextSync() {
    const key = this.syncQueue.shift();
    if (!key) {
      this.patch({ syncing: false });
      this.pending.forEach((entry,id) => { if (entry.phase === 'unknown') this.retryMessage(id); });
      this.send({ type: 'conversations' });
      if (this.state.current) this.loadHistory(this.state.current,true);
      return;
    }
    this.syncPage(key, this.syncCursors.get(key) ?? 0);
  }

  private syncPage(key: string, after: number) {
    const chat = this.state.conversations.find(chat => chat.key === key);
    if (!chat) { this.nextSync(); return; }
    const requestId = messageId();
    if (!this.send({ type: 'history',kind:chat.kind,id:chat.id,after:String(after),limit:50,requestId })) return;
    this.syncPending.set(requestId,{ key, after, timer: setTimeout(() => {
      this.notify('消息同步超时，正在重新连接'); if (this.socket) this.disconnect(this.socket);
    },8000) });
  }

  private receiveSync(data: Extract<ServerEvent,{type:'history'}>) {
    const pending = this.syncPending.get(data.requestId);
    if (!pending || pending.key !== `${data.kind}:${data.id}` || !this.state.session) return;
    clearTimeout(pending.timer); this.syncPending.delete(data.requestId);
    const chat = this.state.conversations.find(chat => chat.key === pending.key);
    if (!chat) { this.nextSync(); return; }
    data.messages.forEach(message => {
      if (message.fromUserId === this.state.session?.userId && message.clientMessageId && this.pending.get(message.clientMessageId)?.key === pending.key)
        this.finishPending(message.clientMessageId,undefined,message);
    });
    const current = this.state.conversations.find(chat => chat.key === pending.key)!;
    const history: Message[] = data.messages.map(message => ({ id:message.id,time:message.time,sequence:message.sequence,
      fromUserId:message.fromUserId,clientMessageId:message.clientMessageId,mine:message.fromUserId===this.state.session?.userId,
      name:message.fromUsername,text:message.message,...(message.fromUserId===this.state.session?.userId?{status:'已发送'}:{}) }));
    const merged = new Map([...history,...current.messages].map(message => [message.id,message]));
    this.updateChat(chat.key,{messages:[...merged.values()].sort((a,b)=>(a.sequence ?? Infinity)-(b.sequence ?? Infinity) || a.time-b.time)});
    const next = Number(data.nextAfter ?? Math.max(pending.after,...data.messages.map(message=>message.sequence ?? 0)));
    if (data.hasMore) {
      if (!Number.isSafeInteger(next) || next <= pending.after) { this.socket && this.disconnect(this.socket); return; }
      this.syncPage(pending.key,next);
    } else {
      this.syncCursors.set(pending.key,Math.max(pending.after,next,this.syncTarget,data.syncCursor ?? 0));
      this.persist(); this.nextSync();
    }
  }

  private receive(data: ServerEvent) {
    switch (data.type) {
      case 'heartbeat': if (!this.state.restoring) this.retry = 0; break;
      case 'sessionInvalid': this.invalidate(data.reason); break;
      case 'register':
      case 'changePassword': {
        if (!this.accountRequest || this.accountRequest.type !== data.type || this.accountRequest.id !== data.requestId) return;
        this.finishAccount(data.success ? '' : data.reason || '账号操作失败，请重试');
        if (!data.success) return;
        if (data.type === 'register') this.patch({ accountNotice: '注册成功，请使用新账号登录' });
        else {
          this.invalidate('密码已修改，请重新登录。');
          this.patch({ accountNotice: '密码已修改，请重新登录。' });
          this.socket?.close();
        }
        break;
      }
      case 'resume':
      case 'login':
        clearTimeout(this.loginTimer);
        this.patch({ loginPending: false, restoring: false });
        if (!data.success) {
          if (data.type === 'resume') this.invalidate(this.chatOwner ? data.reason || '登录凭证已失效，请重新登录' : '');
          else this.patch({ loginError: data.reason || '账号或密码错误，请重试。' });
          return;
        }
        const recover = data.type === 'resume' || this.chatOwner === data.userId;
        if (this.chatOwner !== data.userId) this.resetChat();
        this.chatOwner = data.userId;
        this.blockRestore(false); this.connectionId = data.connectionId;
        if (data.type === 'login' && data.sessionTicket) this.saveCredential(data.sessionTicket,this.socket);
        this.recoverySnapshot = recover;
        this.retry = 0;
        this.patch({ session: { userId: data.userId, username: data.username }, syncing: recover, loginError: '' });
        try { localStorage.setItem('xim.username', data.username); } catch { /* Storage is optional. */ }
        this.send({ type: 'users' });
        this.send({ type: 'conversations' });
        if (this.state.tab === 'users') this.queryContacts(false);
        if (recover) this.snapshotTimer = setTimeout(() => {
          this.notify('会话同步超时，正在重新连接'); if (this.socket) this.disconnect(this.socket);
        },8000);
        break;
      case 'conversations':
        if (!this.state.session) return;
        data.conversations.forEach(chat => this.applySummary(chat, data.syncCursor ?? Number.MAX_SAFE_INTEGER));
        // Snapshot version for summary merging; replay frontiers live only in syncCursors.
        this.patch({ syncCursor: Math.max(this.state.syncCursor, data.syncCursor ?? 0) });
        if (this.recoverySnapshot) {
          clearTimeout(this.snapshotTimer);
          const allowedGroups = new Set(data.conversations.filter(chat => chat.kind === 'group').map(chat => `group:${chat.id}`));
          const removed = this.state.conversations.filter(chat => chat.kind === 'group' && !allowedGroups.has(chat.key));
          removed.forEach(chat => {
            this.syncCursors.delete(chat.key);
            this.pending.forEach((entry,id) => { if (entry.key === chat.key) { clearTimeout(entry.timer); this.pending.delete(id); } });
          });
          this.patch({ conversations: this.state.conversations.filter(chat => !removed.includes(chat)),
            ...(removed.some(chat => chat.key === this.state.current) ? { current: null, showChat: false } : {}) });
          this.recoverySnapshot = false;
          this.syncTarget = data.syncCursor ?? 0;
          this.syncQueue = data.conversations.map(chat => `${chat.kind}:${chat.id}`);
          this.patch({ syncing: true }); this.nextSync();
        } else if (!this.state.syncing) data.conversations.forEach(chat => {
          const key = `${chat.kind}:${chat.id}`;
          if (!this.syncCursors.has(key)) this.syncCursors.set(key, data.syncCursor ?? 0);
        });
        break;
      case 'read': {
        const pending = this.readPending.get(data.requestId);
        if (!pending || pending.key !== `${data.conversation.kind}:${data.conversation.id}` || !this.state.session) return;
        clearTimeout(pending.timer); this.readPending.delete(data.requestId);
        this.applySummary(data.conversation, data.syncCursor);
        break;
      }
      case 'history': {
        if (this.syncPending.has(data.requestId)) { this.receiveSync(data); break; }
        const pending = this.historyPending.get(data.requestId);
        if (!pending || pending.key !== `${data.kind}:${data.id}` || !this.state.session) return;
        this.finishHistory(data.requestId);
        data.messages.forEach(message => {
          if (message.fromUserId === this.state.session?.userId && message.clientMessageId
            && this.pending.get(message.clientMessageId)?.key === pending.key)
            this.finishPending(message.clientMessageId, undefined, message);
        });
        const chat = this.state.conversations.find(item => item.key === pending.key);
        if (!chat) return;
        const history: Message[] = data.messages.map(message => ({ id: message.id, time: message.time, sequence: message.sequence, fromUserId: message.fromUserId, clientMessageId: message.clientMessageId,
          mine: message.fromUserId === this.state.session?.userId, name: message.fromUsername,
          text: message.message, ...(message.fromUserId === this.state.session?.userId ? { status: '已发送' } : {}) }));
        const merged = new Map([...history, ...chat.messages].map(message => [message.id, message]));
        this.updateChat(chat.key, { messages: [...merged.values()].sort((a, b) => a.sequence && b.sequence ? a.sequence - b.sequence : a.time - b.time),
          historyLoaded: true, historyLoading: false, hasMore: data.hasMore, nextBefore: data.nextBefore });
        break;
      }
      case 'logout':
        if (!data.success) { this.notify('退出登录失败'); return; }
        this.invalidate(''); break;
      case 'contacts': {
        const request = this.contactRequest;
        if (!request || request.id !== data.requestId || !this.state.session || this.state.restoring) return;
        clearTimeout(request.timer); this.contactRequest = undefined;
        const users = data.users.filter(user => user.userId !== this.state.session!.userId);
        const merged = new Map([...(request.append ? this.state.directory.users : []), ...users].map(user => [user.userId, user]));
        this.patchDirectory({ users: [...merged.values()], loading: false, loaded: true, error: '', hasMore: data.hasMore, nextAfter: data.nextAfter ?? null });
        break;
      }
      case 'users':
        if (this.state.session && !this.state.restoring) this.patch({ users: data.users,
          directory: { ...this.state.directory, users: this.state.directory.users.map(user => ({ ...user, online: data.users.some(online => online.userId === user.userId) })) } });
        break;
      case 'message':
        if (!this.state.session || this.state.restoring) return;
        this.appendMessage(this.conversation('user', data.fromUserId,
          this.state.conversations.find(chat => chat.key === `user:${data.fromUserId}`)?.name
            || this.state.directory.users.find(user => user.userId === data.fromUserId)?.displayName || data.fromUsername),
          { mine: false, fromUserId: data.fromUserId, name: data.fromUsername, text: data.message, ...(data.id ? { id: data.id, time: data.time, sequence: data.sequence } : {}) }); break;
      case 'groupMessage': {
        if (!this.state.session || this.state.restoring) return;
        const mine = data.fromUserId === this.state.session?.userId;
        if (mine && data.requestId && this.pending.get(data.requestId)?.key === `group:${data.fromGroupId}`) {
          this.finishPending(data.requestId, undefined, data);
        } else {
          this.appendMessage(this.conversation('group', data.fromGroupId),
            { mine, fromUserId: data.fromUserId, name: data.fromUser, text: data.message, ...(mine ? { status: '已发送', clientMessageId: data.requestId } : {}),
              ...(data.id ? { id: data.id, time: data.time, sequence: data.sequence } : {}) });
        }
        break;
      }
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
      case 'sent': if (data.requestId) this.finishPending(data.requestId, undefined, data); break;
      case 'error':
        if (data.requestId && this.contactRequestIds.has(data.requestId)) {
          if (this.contactRequest?.id === data.requestId) { this.cancelContacts(); this.patchDirectory({ error: data.reason }); }
          break;
        }
        if (data.requestId && this.syncPending.has(data.requestId)) { this.notify('消息同步失败，正在重新连接'); this.socket && this.disconnect(this.socket); break; }
        if (data.requestId && this.readPending.has(data.requestId)) {
          clearTimeout(this.readPending.get(data.requestId)!.timer); this.readPending.delete(data.requestId);
          this.notify(data.reason); break;
        }
        if (this.accountRequest && data.requestId === this.accountRequest.id) { this.finishAccount(data.reason); break; }
        if (data.requestId && this.historyPending.has(data.requestId)) {
          this.finishHistory(data.requestId); this.notify(data.reason); break;
        }
        if (data.requestId && this.pending.has(data.requestId)) this.finishPending(data.requestId, data.reason);
        else if (!this.state.session) {
          clearTimeout(this.loginTimer); this.patch({ loginPending: false, loginError: data.reason });
        } else { this.creatingGroup = false; this.notify(data.reason); }
        break;
    }
  }
}
