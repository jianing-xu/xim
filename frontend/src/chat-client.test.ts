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
  send(data: string) { this.sent.push(JSON.parse(data)); }
  receive(data: ServerEvent) { this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(data) })); }
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
    expect(socket.sent.slice(-2)).toEqual([{ type: 'heartbeat' }, { type: 'users' }]);
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

  it('restores failed messages without overwriting a newer draft', () => {
    login(); client.choose('user', 'peer', 'aaa'); client.setDraft('第一条'); client.sendMessage();
    const first = socket.sent.at(-1)!;
    socket.receive({ type: 'error', requestId: first.requestId as string, reason: '对方已离线，消息未发送' });
    expect(chat().draft).toBe('第一条');
    expect(chat().messages[0].status).toContain('离线');
    client.sendMessage(); client.setDraft('新的草稿');
    vi.advanceTimersByTime(8000);
    expect(chat().draft).toBe('新的草稿');
    expect(chat().messages[1].status).toContain('发送结果未知');
  });

  it('tracks unread messages and clears them when the conversation is selected', () => {
    login();
    socket.receive({ type: 'message', fromUserId: 'peer', fromUsername: 'aaa', message: '你好' });
    expect(client.getSnapshot().conversations[0].unread).toBe(1);
    client.choose('user', 'peer');
    expect(chat().unread).toBe(0);
    socket.receive({ type: 'users', users: [] });
    const sentCount = socket.sent.length;
    client.setDraft('离线不能发送'); client.sendMessage();
    expect(socket.sent).toHaveLength(sentCount);
  });

  it('creates and joins groups, handles broadcasts once, lists members and leaves', () => {
    login(); client.createGroup(['peer']);
    socket.receive({ type: 'createGroup', success: true, groupId: 'group' });
    expect(chat().kind).toBe('group');
    client.setDraft('群聊 🌍'); client.sendMessage();
    expect(chat().messages).toHaveLength(0);
    socket.receive({ type: 'groupMessage', fromGroupId: 'group', fromUserId: 'me', fromUser: 'root', message: '群聊 🌍' });
    socket.receive({ type: 'sent', requestId: socket.sent.at(-1)!.requestId as string });
    expect(chat().messages).toHaveLength(1);
    socket.receive({ type: 'members', groupId: 'group', sessions: [null, { userId: 'me', username: 'root' }] });
    expect(client.getSnapshot().members).toHaveLength(1);
    client.closeMembers();
    socket.receive({ type: 'quitGroup', success: true, groupId: 'group' });
    expect(client.getSnapshot().current).toBeNull();
    socket.receive({ type: 'joinGroup', success: false, groupId: 'missing', reason: 'Group does not exist' });
    expect(client.getSnapshot().toast).toContain('群组不存在');
    socket.receive({ type: 'joinGroup', success: true, groupId: 'group' });
    expect(chat().id).toBe('group');
  });

  it('marks interrupted sends as uncertain, reconnects and requires a fresh login', () => {
    login(); client.choose('user', 'peer'); client.setDraft('未确定消息'); client.sendMessage();
    socket.close();
    expect(client.getSnapshot().session).toBeNull();
    expect(chat().messages[0].status).toContain('发送结果未知');
    vi.advanceTimersByTime(1000);
    const next = Socket.instances.at(-1)!; next.open();
    expect(next.sent).toHaveLength(0);
    socket.receive({ type: 'login', success: true, userId: 'stale', username: 'root' });
    expect(client.getSnapshot().session).toBeNull();
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
