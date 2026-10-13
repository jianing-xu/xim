import { encodeServerEvent, decodeClientEvent } from './protocol-wire';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatClient } from './chat-client';
import type { ServerEvent } from './protocol';

class Socket extends EventTarget {
  static OPEN = 1;
  static instances: Socket[] = [];
  readyState = 0;
  sent: Record<string, unknown>[] = [];
  constructor(readonly url: string) { super(); Socket.instances.push(this); }
  open() { this.readyState = Socket.OPEN; this.dispatchEvent(new Event('open')); }
  send(data: ArrayBuffer) { this.sent.push(decodeClientEvent(new Uint8Array(data))); }
  receive(data: ServerEvent) { this.dispatchEvent(new MessageEvent('message', { data: new Uint8Array(encodeServerEvent(data)).buffer })); }
  close() { this.readyState = 3; this.dispatchEvent(new Event('close')); }
}

let client: ChatClient;
let socket: Socket;

beforeEach(() => {
  vi.useFakeTimers();
  Socket.instances = [];
  vi.stubGlobal('WebSocket', Socket);
  vi.stubGlobal('localStorage', { setItem: vi.fn() });
  client = new ChatClient(() => 'ws://example.test/ws');
  client.start();
  socket = Socket.instances[0];
  socket.open();
  socket.receive({ type: 'resume', success: false });
  socket.sent = [];
});
afterEach(() => { client.stop(); vi.unstubAllGlobals(); vi.useRealTimers(); });

function login() {
  client.login(' root ', 'root');
  socket.receive({ type: 'login', success: true, userId: 'me', username: 'root' });
  socket.receive({ type: 'users', users: [{ userId: 'me', username: 'root' }, { userId: 'peer', username: 'aaa' }] });
}
function chat() { return client.getSnapshot().conversations.find(item => item.key === client.getSnapshot().current)!; }

describe('WebSocket chat lifecycle', () => {
  it('authenticates, refreshes online users and keeps the connection alive', () => {
    login();
    expect(socket.sent[0]).toEqual({ type: 'login', username: 'root', password: 'root' });
    expect(client.getSnapshot().session?.userId).toBe('me');
    vi.advanceTimersByTime(5000);
    expect(socket.sent.slice(-3)).toEqual([{ type: 'heartbeat' }, { type: 'users' }, { type: 'conversations' }]);
  });

  it('allows retry after invalid credentials and closes a timed out login', () => {
    client.login('root', 'wrong');
    socket.receive({ type: 'login', success: false });
    expect(client.getSnapshot().loginPending).toBe(false);
    expect(client.getSnapshot().loginError).toContain('账号或密码错误');
    client.login('root', 'root');
    vi.advanceTimersByTime(8000);
    expect(socket.readyState).toBe(3);
    expect(client.getSnapshot().loginError).toContain('超时');
  });

  it('acknowledges Unicode private messages and preserves drafts between conversations', () => {
    login();
    client.choose('user', 'peer', 'aaa');
    client.setDraft('你好 🌍\n<script>alert(1)</script>');
    client.sendMessage();
    const request = socket.sent.at(-1)!;
    expect(chat().messages[0].text).toBe(request.message);
    expect(chat().messages[0].status).toBe('发送中…');
    socket.receive({ type: 'sent', requestId: request.requestId as string });
    expect(chat().messages[0].status).toBe('已发送');
    client.setDraft('未发送草稿');
    client.choose('group', 'group');
    client.setDraft('群聊草稿');
    client.choose('user', 'peer');
    expect(chat().draft).toBe('未发送草稿');
  });

  it('retries failed messages without restoring or overwriting drafts', () => {
    login(); client.choose('user', 'peer', 'aaa'); client.setDraft('第一条'); client.sendMessage();
    const first = socket.sent.at(-1)!;
    socket.receive({ type: 'error', requestId: first.requestId as string, reason: '对方已离线，消息未发送' });
    expect(chat().draft).toBe('');
    expect(chat().messages[0].retryable).toBe(true);
    expect(chat().messages[0].status).toContain('离线');
    client.retryMessage(first.requestId as string); client.setDraft('新的草稿');
    expect(socket.sent.at(-1)).toEqual(first);
    vi.advanceTimersByTime(8000);
    expect(chat().draft).toBe('新的草稿');
    expect(chat().messages).toHaveLength(1);
    expect(chat().messages[0].status).toContain('发送结果未知');
  });

  it('tracks unread messages without clearing them on selection', () => {
    login();
    socket.receive({ type: 'message', fromUserId: 'peer', fromUsername: 'aaa', message: '你好' });
    expect(client.getSnapshot().conversations[0].unread).toBe(1);
    client.choose('user', 'peer');
    expect(chat().unread).toBe(1);
    socket.receive({ type: 'users', users: [] });
    const sentCount = socket.sent.length;
    client.setDraft('离线消息'); client.sendMessage();
    expect(socket.sent).toHaveLength(sentCount + 1);
    expect(socket.sent.at(-1)?.message).toBe('离线消息');
  });

  it('creates and joins groups, handles broadcasts once, lists members and leaves', () => {
    login(); client.createGroup(['peer']);
    socket.receive({ type: 'createGroup', success: true, groupId: 'group' });
    expect(chat().kind).toBe('group');
    client.setDraft('群聊 🌍'); client.sendMessage();
    expect(chat().messages).toHaveLength(1);
    socket.receive({ type: 'groupMessage', fromGroupId: 'group', fromUserId: 'me', fromUser: 'root', message: '群聊 🌍', requestId: socket.sent.at(-1)!.requestId as string, id: 'group-db', time: 42 });
    socket.receive({ type: 'sent', requestId: socket.sent.at(-1)!.requestId as string });
    expect(chat().messages).toHaveLength(1);
    socket.receive({ type: 'members', groupId: 'group', sessions: [{ userId: 'me', username: 'root' }] });
    expect(client.getSnapshot().members).toHaveLength(1);
    client.closeMembers();
    socket.receive({ type: 'quitGroup', success: true, groupId: 'group' });
    expect(client.getSnapshot().current).toBeNull();
    socket.receive({ type: 'joinGroup', success: false, groupId: 'missing', reason: 'Group does not exist' });
    expect(client.getSnapshot().toast).toContain('群组不存在');
    socket.receive({ type: 'joinGroup', success: true, groupId: 'group' });
    expect(chat().id).toBe('group');
  });

  it('marks interrupted sends as uncertain and reconnects using a credential', () => {
    login(); client.choose('user', 'peer'); client.setDraft('未确定消息'); client.sendMessage();
    socket.close();
    expect(client.getSnapshot().session?.userId).toBe('me');
    expect(chat().messages[0].status).toContain('发送结果未知');
    vi.advanceTimersByTime(1000);
    const next = Socket.instances.at(-1)!; next.open();
    expect(next.sent).toEqual([{ type: 'resume' }]);
    socket.receive({ type: 'login', success: true, userId: 'stale', username: 'root' });
    expect(client.getSnapshot().session?.userId).toBe('me');
    next.receive({ type: 'login', success: true, userId: 'new', username: 'aaa' });
    expect(client.getSnapshot().conversations).toHaveLength(0);
  });

  it('supports StrictMode cleanup without stale sockets or duplicate heartbeat timers', () => {
    client.stop(); client.start();
    const next = Socket.instances.at(-1)!; next.open();
    socket.open();
    vi.advanceTimersByTime(5000);
    expect(socket.sent).toHaveLength(0);
    expect(next.sent).toEqual([{ type: 'heartbeat' }]);
    client.stop(); vi.advanceTimersByTime(20000);
    expect(Socket.instances).toHaveLength(2);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('clears session and conversations on logout while allowing login on the same socket', () => {
    login(); client.choose('user', 'peer'); client.setDraft('草稿');
    socket.receive({ type: 'logout', success: true });
    expect(client.getSnapshot().session).toBeNull();
    expect(client.getSnapshot().conversations).toHaveLength(0);
    expect(socket.readyState).toBe(Socket.OPEN);
    login(); expect(client.getSnapshot().session?.username).toBe('root');
  });
});

describe('persistent conversation recovery', () => {
  it('restores conversations after fresh login and merges history with live messages once', () => {
    login();
    expect(socket.sent).toContainEqual({ type: 'conversations' });
    socket.receive({ type: 'conversations', conversations: [{ kind: 'user', id: 'peer', name: 'aaa' }, { kind: 'group', id: 'g', name: '群聊 g' }] });
    client.choose('user', 'peer');
    const request = socket.sent.at(-1)!;
    expect(request.type).toBe('history');
    socket.receive({ type: 'message', fromUserId: 'peer', fromUsername: 'aaa', message: 'live', id: 'live', time: 2000 });
    socket.receive({ type: 'history', requestId: request.requestId as string, kind: 'user', id: 'peer', hasMore: true, nextBefore: '10', messages: [
      { id: 'old', time: 1000, fromUserId: 'me', fromUsername: 'root', message: 'old' },
      { id: 'live', time: 2000, fromUserId: 'peer', fromUsername: 'aaa', message: 'live' },
    ] });
    expect(chat().messages.map(message => message.id)).toEqual(['old', 'live']);
    expect(chat().messages[0].mine).toBe(true);
    expect(chat().messages[0].time).toBe(1000);
    client.loadHistory(chat().key);
    expect(socket.sent.at(-1)?.before).toBe('10');
    const older = socket.sent.at(-1)!;
    socket.receive({ type: 'history', requestId: older.requestId as string, kind: 'user', id: 'peer', hasMore: false, nextBefore: '1', messages: [
      { id: 'first', time: 500, fromUserId: 'peer', fromUsername: 'aaa', message: 'first' },
    ] });
    expect(chat().messages.map(message => message.id)).toEqual(['first', 'old', 'live']);
    const count = socket.sent.length;
    client.loadHistory(chat().key);
    expect(socket.sent).toHaveLength(count);
  });

  it('assigns committed IDs to optimistic messages so history cannot duplicate them', () => {
    login(); client.choose('user', 'peer');
    const history = socket.sent.at(-1)!;
    client.setDraft('sent'); client.sendMessage();
    const sent = socket.sent.at(-1)!;
    socket.receive({ type: 'sent', requestId: sent.requestId as string, id: 'db-id', time: 42 });
    socket.receive({ type: 'history', requestId: history.requestId as string, kind: 'user', id: 'peer', hasMore: false, nextBefore: '1', messages: [
      { id: 'db-id', time: 42, fromUserId: 'me', fromUsername: 'root', message: 'sent' },
    ] });
    expect(chat().messages).toHaveLength(1);
    expect(chat().messages[0]).toMatchObject({ id: 'db-id', time: 42, status: '已发送' });
  });

  it('allows retry after history error/timeout and ignores responses from a previous login', () => {
    login(); client.choose('group', 'g');
    const request = socket.sent.at(-1)!;
    socket.receive({ type: 'error', requestId: request.requestId as string, reason: '存储操作失败' });
    expect(chat().historyLoading).toBe(false);
    client.loadHistory(chat().key); vi.advanceTimersByTime(8000);
    expect(chat().historyLoading).toBe(false);
    expect(client.getSnapshot().toast).toContain('超时');
    socket.receive({ type: 'logout', success: true }); login();
    socket.receive({ type: 'history', requestId: request.requestId as string, kind: 'group', id: 'g', messages: [], hasMore: false, nextBefore: null });
    expect(client.getSnapshot().conversations).toHaveLength(0);
  });
});


describe('reliable logical message retries', () => {
  it.each(['user', 'group'] as const)('corrects a late %s acknowledgement after timeout without restoring a draft', kind => {
    login(); client.choose(kind, 'peer');
    client.setDraft('one'); client.sendMessage();
    const sent = socket.sent.at(-1)!;
    vi.advanceTimersByTime(8000);
    expect(chat().draft).toBe('');
    expect(chat().messages[0].retryable).toBe(true);
    client.setDraft('new input');
    socket.receive({ type: 'sent', requestId: sent.requestId as string, id: 'committed', time: 123 });
    expect(chat().messages).toHaveLength(1);
    expect(chat().messages[0]).toMatchObject({ id: 'committed', time: 123, status: '已发送', retryable: false });
    expect(chat().draft).toBe('new input');
    const count = socket.sent.length;
    client.retryMessage(sent.requestId as string);
    expect(socket.sent).toHaveLength(count);
    // A stale timeout/error from an earlier attempt cannot undo committed success.
    socket.receive({ type: 'error', requestId: sent.requestId as string, reason: 'stale error' });
    vi.advanceTimersByTime(8000);
    expect(chat().messages[0].status).toBe('已发送');
  });

  it.each(['user', 'group'] as const)('reuses the %s client ID after a lost acknowledgement and blocks repeated retry clicks', kind => {
    login(); client.choose(kind, 'peer'); client.setDraft('same body'); client.sendMessage();
    const sent = socket.sent.at(-1)!;
    vi.advanceTimersByTime(8000);
    client.setDraft('independent draft'); client.retryMessage(sent.requestId as string);
    expect(socket.sent.at(-1)).toEqual(sent);
    const count = socket.sent.length;
    client.retryMessage(sent.requestId as string);
    expect(socket.sent).toHaveLength(count);
    socket.receive({ type: 'sent', requestId: sent.requestId as string, id: 'saved', time: 99 });
    socket.receive({ type: 'sent', requestId: sent.requestId as string, id: 'saved', time: 99 });
    expect(chat().messages).toHaveLength(1);
    expect(chat().messages[0].status).toBe('已发送');
    expect(chat().draft).toBe('independent draft');
  });

  it.each(['broadcast-first', 'ack-first'] as const)('merges group echo, receipt and history in %s order', order => {
    login(); client.choose('group', 'g');
    const history = socket.sent.at(-1)!;
    client.setDraft('echo'); client.sendMessage();
    const requestId = socket.sent.at(-1)!.requestId as string;
    const ack: ServerEvent = { type: 'sent', requestId, id: 'saved', time: 10 };
    const echo: ServerEvent = { type: 'groupMessage', fromGroupId: 'g', fromUserId: 'me', fromUser: 'root',
      requestId, message: 'echo', id: 'saved', time: 10 };
    socket.receive(order === 'broadcast-first' ? echo : ack);
    socket.receive(order === 'broadcast-first' ? ack : echo);
    socket.receive(echo);
    socket.receive({ type: 'history', requestId: history.requestId as string, kind: 'group', id: 'g', hasMore: false,
      nextBefore: null, messages: [{ id: 'saved', time: 10, fromUserId: 'me', fromUsername: 'root', message: 'echo', clientMessageId: requestId }] });
    expect(chat().messages).toHaveLength(1);
    expect(chat().messages[0]).toMatchObject({ id: 'saved', status: '已发送', retryable: false });
  });

  it.each(['user', 'group'] as const)('uses %s history to correct unknown sends before their receipt arrives', kind => {
    login(); client.choose(kind, 'peer'); const history = socket.sent.at(-1)!;
    client.setDraft('committed'); client.sendMessage(); const requestId = socket.sent.at(-1)!.requestId as string;
    vi.advanceTimersByTime(8000); client.loadHistory(chat().key); const reload = socket.sent.at(-1)!;
    socket.receive({ type: 'history', requestId: reload.requestId as string, kind, id: 'peer', hasMore: false,
      nextBefore: '1', messages: [{ id: 'db', time: 10, fromUserId: 'me', fromUsername: 'root', message: 'committed', clientMessageId: requestId }] });
    socket.receive({ type: 'sent', requestId, id: 'db', time: 10 });
    expect(history.type).toBe('history');
    expect(chat().messages).toHaveLength(1);
    expect(chat().messages[0]).toMatchObject({ id: 'db', status: '已发送', retryable: false });
    expect(chat().draft).toBe('');
  });

  it('retains unknown sends across reconnect and same-account login, while isolating another account', () => {
    login(); client.choose('user', 'peer'); client.setDraft('retry after reconnect'); client.sendMessage();
    const sent = socket.sent.at(-1)!;
    socket.close(); client.retryMessage(sent.requestId as string);
    vi.advanceTimersByTime(1000); const next = Socket.instances.at(-1)!; next.open();
    client.login('root', 'root'); next.receive({ type: 'login', success: true, userId: 'me', username: 'root' });
    next.receive({ type: 'conversations', conversations: [] });
    expect(chat().messages).toHaveLength(1);
    client.retryMessage(sent.requestId as string); expect(next.sent).toContainEqual(sent);
    next.receive({ type: 'sent', requestId: sent.requestId as string, id: 'db', time: 1 });
    expect(chat().messages[0].status).toBe('已发送');
    next.close(); vi.advanceTimersByTime(1000); const other = Socket.instances.at(-1)!; other.open();
    other.receive({ type: 'login', success: true, userId: 'different', username: 'aaa' });
    expect(client.getSnapshot().conversations).toHaveLength(0);
    const count = other.sent.length; client.retryMessage(sent.requestId as string);
    other.receive({ type: 'sent', requestId: sent.requestId as string, id: 'db', time: 1 });
    expect(other.sent).toHaveLength(count);
    expect(client.getSnapshot().conversations).toHaveLength(0);
  });
});


describe('retry mapping isolation and cleanup', () => {
  it('scopes a client ID to its sender when history contains another user with the same ID', () => {
    login(); client.choose('user', 'peer'); const history = socket.sent.at(-1)!;
    client.setDraft('mine'); client.sendMessage(); const requestId = socket.sent.at(-1)!.requestId as string;
    socket.receive({ type: 'history', requestId: history.requestId as string, kind: 'user', id: 'peer', hasMore: false,
      nextBefore: '1', messages: [{ id: 'peer-db', time: 1, fromUserId: 'peer', fromUsername: 'aaa', message: 'theirs', clientMessageId: requestId }] });
    socket.receive({ type: 'sent', requestId, id: 'my-db', time: 2 });
    expect(chat().messages).toHaveLength(2);
    expect(chat().messages[0]).toMatchObject({ id: 'peer-db', mine: false, text: 'theirs' });
    expect(chat().messages[1]).toMatchObject({ id: 'my-db', mine: true, text: 'mine', status: '已发送' });
  });

  it('clears all timers on stop with an in-flight message and preserves retry identity on restart', () => {
    login(); client.choose('user', 'peer'); client.setDraft('in flight'); client.sendMessage();
    const sent = socket.sent.at(-1)!;
    client.stop(); expect(vi.getTimerCount()).toBe(0);
    expect(chat().messages[0].retryable).toBe(true);
    client.start(); const next = Socket.instances.at(-1)!; next.open();
    next.receive({ type: 'login', success: true, userId: 'me', username: 'root' });
    next.receive({ type: 'conversations', conversations: [] });
    client.retryMessage(sent.requestId as string);
    expect(next.sent).toContainEqual(sent);
  });
});


describe('account management', () => {
  it('registers with correlation and keeps passwords out of state and persistence', () => {
    expect(client.register(' new_user ', 'private-password')).toBe(true);
    const request = socket.sent.at(-1)!;
    expect(request.type).toBe('register'); expect(request.username).toBe('new_user');
    expect(JSON.stringify(client.getSnapshot())).not.toContain('private-password');
    expect(client.register('another_user', 'private-password')).toBe(false);
    socket.receive({ type: 'register', success: true, requestId: 'unrelated' });
    expect(client.getSnapshot().accountPending).toBe(true);
    socket.receive({ type: 'register', success: true, requestId: request.requestId as string });
    expect(client.getSnapshot().accountPending).toBe(false);
    expect(client.getSnapshot().session).toBeNull();
    expect(client.getSnapshot().accountNotice).toContain('注册成功');
    expect(localStorage.setItem).not.toHaveBeenCalled();
  });

  it('shows duplicate or invalid input errors and permits correction', () => {
    client.register('new_user', 'private-password');
    socket.receive({ type: 'register', success: false, reason: '账号已存在', requestId: socket.sent.at(-1)!.requestId as string });
    expect(client.getSnapshot().accountError).toContain('已存在');
    expect(client.register('other_user', 'private-password')).toBe(true);
    socket.receive({ type: 'error', reason: '账号或密码格式错误', requestId: socket.sent.at(-1)!.requestId as string });
    expect(client.getSnapshot().accountPending).toBe(false);
  });

  it('handles password failure and forces fresh login after successful change', () => {
    login();
    expect(client.changePassword('private-old', 'private-new')).toBe(true);
    const first = socket.sent.at(-1)!;
    socket.receive({ type: 'changePassword', success: false, reason: '账号或密码错误', requestId: first.requestId as string });
    expect(client.getSnapshot().session?.userId).toBe('me');
    expect(client.getSnapshot().accountError).toContain('密码错误');
    client.changePassword('private-old', 'private-new');
    expect(JSON.stringify(client.getSnapshot())).not.toContain('private-old');
    expect(JSON.stringify(client.getSnapshot())).not.toContain('private-new');
    socket.receive({ type: 'changePassword', success: true, requestId: socket.sent.at(-1)!.requestId as string });
    expect(client.getSnapshot().session).toBeNull(); expect(socket.readyState).toBe(3);
    expect(client.getSnapshot().loginError).toContain('密码已修改');
    expect(localStorage.setItem).toHaveBeenCalledExactlyOnceWith('xim.username', 'root');
  });

  it('clears pending operations on disconnect and timeout without retrying credentials', () => {
    client.register('new_user', 'private-password');
    vi.advanceTimersByTime(8000);
    expect(client.getSnapshot().accountPending).toBe(false);
    expect(client.getSnapshot().accountError).toContain('结果未确认');
    vi.advanceTimersByTime(1000); const next = Socket.instances.at(-1)!; next.open();
    expect(next.sent).toHaveLength(0);
    client.register('new_user', 'private-password'); next.close();
    expect(client.getSnapshot().accountPending).toBe(false);
    expect(client.getSnapshot().accountError).toContain('连接中断');
  });

  it('displays the server login throttle reason', () => {
    client.login('root', 'wrong');
    socket.receive({ type: 'login', success: false, reason: '尝试过于频繁，请 5 分钟后重试。' });
    expect(client.getSnapshot().loginError).toContain('5 分钟');
  });
});

describe('durable summaries and reading positions', () => {
  function summary(id = 'peer', sequence = 65, unread = 65, readSequence = 0) {
    return { kind: 'user' as const, id, name: id, lastSequence: sequence, lastTime: sequence * 100,
      readSequence, unread, lastMessage: { id: `m${sequence}`, sequence, time: sequence * 100,
        fromUserId: id, fromUsername: id, message: `摘要 ${sequence}` } };
  }
  function recent(sequence = 65) {
    client.choose('user', 'peer');
    const request = socket.sent.at(-1)!;
    socket.receive({ type: 'history', requestId: request.requestId as string, kind: 'user', id: 'peer',
      messages: [{ id: `m${sequence}`, sequence, time: sequence * 100, fromUserId: 'peer', fromUsername: 'peer', message: 'latest' }],
      hasMore: true, nextBefore: '16' });
  }
  function reads() { return socket.sent.filter(event => event.type === 'read'); }

  it('restores full offline unread counts, summaries and sequence order on a fresh page', () => {
    login();
    socket.receive({ type: 'conversations', syncCursor: '90', conversations: [summary('peer', 65), summary('later', 90, 2)] });
    expect(client.getSnapshot().conversations.map(chat => chat.id)).toEqual(['later', 'peer']);
    expect(client.getSnapshot().conversations[1]).toMatchObject({ unread: 65, messages: [], lastMessage: { message: '摘要 65' } });
    recent();
    expect(chat().unread).toBe(65);
    expect(reads()).toHaveLength(0);
    client.reportReading(chat().key, true, true);
    const read = reads().at(-1)!;
    expect(read.sequence).toBe('65'); expect(chat().unread).toBe(65);
    socket.receive({ type: 'read', requestId: read.requestId as string, syncCursor: '90', conversation: summary('peer',65,0,65) });
    expect(chat().unread).toBe(0); expect(chat().readSequence).toBe('65');
    client.stop(); client = new ChatClient(() => 'ws://example.test/ws'); client.start();
    socket = Socket.instances.at(-1)!; socket.open(); login();
    socket.receive({ type: 'conversations', syncCursor: '90', conversations: [summary('peer',65,0,65)] });
    expect(client.getSnapshot().conversations[0]).toMatchObject({ unread: 0, readSequence: '65', messages: [] });
  });

  it('never clears while hidden, in the list, before rendering, or while browsing older messages', () => {
    login(); socket.receive({ type: 'conversations', syncCursor: '65', conversations: [summary()] });
    recent();
    client.reportReading(chat().key, false, true); client.reportReading(chat().key, true, false);
    vi.stubGlobal('document', { visibilityState: 'hidden' }); client.reportReading(chat().key, true, true);
    vi.stubGlobal('document', { visibilityState: 'visible' });
    client.back(); client.reportReading(chat().key, true, true);
    client.switchTab('users'); client.reportReading(chat().key, true, true);
    expect(reads()).toHaveLength(0); expect(chat().unread).toBe(65);
    recent(); client.reportReading(chat().key, true, true);
    client.reportReading(chat().key, true, true);
    expect(reads()).toHaveLength(1);
  });

  it('counts live and duplicate messages once and excludes own private/group sends', () => {
    login(); socket.receive({ type: 'conversations', syncCursor: '65', conversations: [summary()] });
    const incoming: ServerEvent = { type: 'message', fromUserId: 'peer', fromUsername: 'peer', message: 'new', id: 'new', sequence: 66, time: 100 };
    socket.receive(incoming); socket.receive(incoming);
    expect(client.getSnapshot().conversations[0]).toMatchObject({ unread: 66, lastSequence: '66', lastMessage: { message: 'new' } });
    recent(66); client.setDraft('mine'); client.sendMessage();
    const send = socket.sent.at(-1)!;
    socket.receive({ type: 'sent', requestId: send.requestId as string, id: 'own', sequence: 67, time: 1 });
    socket.receive({ type: 'sent', requestId: send.requestId as string, id: 'own', sequence: 67, time: 1 });
    expect(chat().unread).toBe(66); expect(chat().lastSequence).toBe('67');
    socket.receive({ type: 'groupMessage', fromGroupId: 'g', fromUserId: 'me', fromUser: 'root', message: 'own group', sequence: 68, id: 'g-own', time: 1 });
    const group = client.getSnapshot().conversations.find(chat => chat.id === 'g')!;
    expect(group.unread).toBe(0); expect(client.getSnapshot().conversations[0].id).toBe('g');
    socket.receive({ type: 'groupMessage', fromGroupId: 'g', fromUserId: 'peer', fromUser: 'peer', message: 'group', sequence: 69, id: 'g-other', time: 1 });
    socket.receive({ type: 'groupMessage', fromGroupId: 'g', fromUserId: 'peer', fromUser: 'peer', message: 'group', sequence: 69, id: 'g-other', time: 1 });
    expect(client.getSnapshot().conversations[0].unread).toBe(1);
  });

  it('merges an initial late snapshot baseline with pushes beyond its cursor', () => {
    login();
    socket.receive({ type: 'message', fromUserId: 'peer', fromUsername: 'peer', message: 'eleven', id: 'm11', sequence: 11, time: 11 });
    socket.receive({ type: 'conversations', syncCursor: '10', conversations: [summary('peer',10,3)] });
    expect(client.getSnapshot().conversations[0]).toMatchObject({ unread: 4, lastSequence: '11', lastMessage: { message: 'eleven' }, readSequence: '0' });
    expect(socket.sent.at(-1)?.type).toBe('conversations');
    socket.receive({ type: 'conversations', syncCursor: '11', conversations: [summary('peer',11,4)] });
    expect(client.getSnapshot().conversations[0].unread).toBe(4);
    socket.receive({ type: 'conversations', syncCursor: '10', conversations: [summary('peer',10,3)] });
    expect(client.getSnapshot().conversations[0].unread).toBe(4);
  });

  it('retains a push after a reading snapshot and ignores older reading updates', () => {
    login(); socket.receive({ type: 'conversations', syncCursor: '10', conversations: [summary('peer',10,3)] });
    recent(10); client.reportReading(chat().key,true,true); const read=reads().at(-1)!;
    socket.receive({ type: 'message', fromUserId: 'peer', fromUsername: 'peer', message: 'eleven', id: 'm11', sequence: 11, time: 11 });
    socket.receive({ type: 'conversations', syncCursor: '11', conversations: [{ ...summary('peer',11,4,0), lastMessage: { ...summary('peer',11,4,0).lastMessage, message: 'eleven' } }] });
    socket.receive({ type: 'read', requestId: read.requestId as string, syncCursor: '10', conversation: summary('peer',10,0,10) });
    expect(chat()).toMatchObject({ unread: 1, readSequence: '10', lastSequence: '11', lastMessage: { message: 'eleven' } });
    socket.receive({ type: 'conversations', syncCursor: '11', conversations: [summary('peer',11,4,0)] });
    expect(chat()).toMatchObject({ unread: 1, readSequence: '10' });
    socket.receive({ type: 'conversations', syncCursor: '11', conversations: [summary('peer',11,1,10)] });
    client.reportReading(chat().key,true,true); const next=reads().at(-1)!;
    socket.receive({ type: 'read', requestId: next.requestId as string, syncCursor: '11', conversation: summary('peer',11,0,11) });
    socket.receive({ type: 'read', requestId: read.requestId as string, syncCursor: '10', conversation: summary('peer',10,0,10) });
    expect(chat()).toMatchObject({ unread: 0, readSequence: '11', lastSequence: '11' });
  });

  it('does not count pagination or snapshot-covered pushes twice and refreshes a missing tail', () => {
    login(); socket.receive({ type: 'conversations', syncCursor: '65', conversations: [summary()] }); recent();
    socket.receive({ type: 'message', fromUserId: 'peer', fromUsername: 'peer', message: 'known', id: 'm64', sequence: 64, time: 64 });
    expect(chat().unread).toBe(65);
    client.loadHistory(chat().key); const older=socket.sent.at(-1)!;
    socket.receive({ type: 'history', requestId: older.requestId as string, kind: 'user', id: 'peer', hasMore: false, nextBefore: '1',
      messages: [{ id: 'm1', sequence: 1, time: 1, fromUserId: 'peer', fromUsername: 'peer', message: 'old' }] });
    expect(chat().unread).toBe(65); expect(chat().lastSequence).toBe('65');
    socket.receive({ type: 'conversations', syncCursor: '80', conversations: [summary('peer',80,80)] });
    expect(socket.sent.at(-1)).toMatchObject({ type: 'history', id: 'peer' });
    expect(socket.sent.at(-1)?.before).toBeUndefined();
    client.reportReading(chat().key,true,true); expect(reads()).toHaveLength(0);
  });

  it('keeps unread on read failures/timeouts and clears pending reading on account changes', () => {
    login(); socket.receive({ type: 'conversations', syncCursor: '65', conversations: [summary()] }); recent();
    client.reportReading(chat().key,true,true); const read=reads().at(-1)!;
    socket.receive({ type: 'error', requestId: read.requestId as string, reason: '请先加入该群组' });
    expect(chat().unread).toBe(65); expect(client.getSnapshot().toast).toContain('加入');
    client.reportReading(chat().key,true,true); vi.advanceTimersByTime(8000);
    expect(chat().unread).toBe(65); expect(client.getSnapshot().toast).toContain('阅读位置同步超时');
    client.reportReading(chat().key,true,true); const pending=reads().at(-1)!;
    socket.receive({ type: 'logout', success: true }); login();
    socket.receive({ type: 'read', requestId: pending.requestId as string, syncCursor: '65', conversation: summary('peer',65,0,65) });
    expect(client.getSnapshot().conversations).toHaveLength(0);
  });
});
