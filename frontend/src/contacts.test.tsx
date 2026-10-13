import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { ChatClient } from './chat-client';
import type { Contact, ServerEvent } from './protocol';
import { Sidebar } from './components/Sidebar';
import { GroupDialogs } from './components/GroupDialogs';
import { ContactStatus } from './components/ContactDirectory';

class Socket extends EventTarget {
  static OPEN = 1;
  static all: Socket[] = [];
  readyState = 0;
  sent: Record<string, unknown>[] = [];
  constructor(readonly url: string) { super(); Socket.all.push(this); }
  open() { this.readyState = 1; this.dispatchEvent(new Event('open')); }
  send(value: string) { this.sent.push(JSON.parse(value)); }
  close() { this.readyState = 3; this.dispatchEvent(new Event('close')); }
  receive(data: ServerEvent) { this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(data) })); }
}
let client: ChatClient, socket: Socket;
const contact = (userId: string, online = false): Contact => ({ userId, username: userId + '_account', displayName: '伙伴 ' + userId, online });
function request() { return socket.sent.filter(event => event.type === 'contacts').at(-1)!; }
function page(users: Contact[], more = false, id = request().requestId as string) {
  socket.receive({ type: 'contacts', requestId: id, users, hasMore: more, nextAfter: more ? users.at(-1)!.userId : null });
}
function current() { return client.getSnapshot().conversations.find(chat => chat.key === client.getSnapshot().current)!; }
beforeEach(() => {
  vi.useFakeTimers(); Socket.all = []; vi.stubGlobal('WebSocket', Socket);
  const values = new Map<string, string>();
  const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) };
  vi.stubGlobal('localStorage', storage); vi.stubGlobal('sessionStorage', storage);
  client = new ChatClient(() => 'ws://example.test/ws'); client.start(); socket = Socket.all[0]; socket.open();
  socket.receive({ type: 'resume', success: false });
  client.login('me_user', 'private-password');
  socket.receive({ type: 'login', success: true, userId: 'me', username: 'me_user' });
  socket.receive({ type: 'conversations', conversations: [], syncCursor: 0 });
});
afterEach(() => { client.stop(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('authenticated contact directory', () => {
  it('starts a first offline conversation and merges repeated selections with summaries and unread', () => {
    client.switchTab('users'); page([contact('offline')]);
    const user = client.getSnapshot().directory.users[0];
    client.choose('user', user.userId, user.displayName); client.setDraft('离线首聊'); client.sendMessage();
    const sent = socket.sent.at(-1)!;
    expect(sent).toMatchObject({ type: 'message', toUserId: 'offline', message: '离线首聊' });
    socket.receive({ type: 'sent', requestId: sent.requestId as string, id: 'db', sequence: 1, time: 100 });
    socket.receive({ type: 'conversations', syncCursor: 2, conversations: [{ kind: 'user', id: 'offline', name: user.displayName, unread: 1, lastSequence: 2 }] });
    client.choose('user', user.userId, user.displayName);
    expect(client.getSnapshot().conversations).toHaveLength(1); expect(current().messages).toHaveLength(1);
    expect(current().unread).toBe(1); expect(client.isOnline(current())).toBe(false);
    socket.receive({ type: 'users', users: [{ userId: 'offline', username: 'offline_account' }] });
    expect(client.isOnline(current())).toBe(true);
  });
  it('debounces search, ignores old pages and errors, filters self and merges pagination once', () => {
    client.switchTab('users'); const old = request();
    client.searchContacts('a'); client.searchContacts('new'); vi.advanceTimersByTime(250);
    expect(request()).toMatchObject({ query: 'new', limit: 20 });
    page([contact('old')], false, old.requestId as string);
    socket.receive({ type: 'error', requestId: old.requestId as string, reason: 'old failure' });
    expect(client.getSnapshot().directory.users).toEqual([]); expect(client.getSnapshot().toast).toBe('');
    page([contact('me'), contact('first')], true);
    expect(client.getSnapshot().directory.users.map(user => user.userId)).toEqual(['first']);
    client.loadMoreContacts(); expect(request().after).toBe('first');
    page([contact('first'), contact('second')]);
    expect(client.getSnapshot().directory.users.map(user => user.userId)).toEqual(['first', 'second']);
    const count = socket.sent.length; client.loadMoreContacts(); expect(socket.sent).toHaveLength(count);
  });
  it('shows failed pages without discarding earlier rows and retries the same cursor', () => {
    client.switchTab('users'); page([contact('first')], true); client.loadMoreContacts();
    socket.receive({ type: 'error', requestId: request().requestId as string, reason: '查询失败' });
    expect(client.getSnapshot().directory).toMatchObject({ loading: false, error: '查询失败', users: [contact('first')] });
    client.retryContacts(); expect(request().after).toBe('first'); page([contact('second')]);
    expect(client.getSnapshot().directory.users).toHaveLength(2);
    client.searchContacts('missing'); vi.advanceTimersByTime(250); page([]);
    expect(client.getSnapshot().directory).toMatchObject({ loaded: true, users: [], error: '' });
  });
  it('handles timeout, interruption and account change without accepting stale responses', () => {
    client.switchTab('users'); const old = request(); vi.advanceTimersByTime(8000);
    expect(client.getSnapshot().directory.error).toContain('超时');
    client.retryContacts(); socket.close();
    expect(client.getSnapshot().directory).toMatchObject({ loading: false, error: '连接已断开，请恢复后重试' });
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    vi.advanceTimersByTime(1000); socket = Socket.all.at(-1)!; socket.open();
    socket.receive({ type: 'resume', success: false });
    client.login('other_user', 'private-password');
    socket.receive({ type: 'login', success: true, userId: 'other', username: 'other_user' });
    page([contact('old')], false, old.requestId as string);
    expect(client.getSnapshot().directory.users).toEqual([]);
  });
  it('keeps group member IDs independent of search pages and sends offline members', () => {
    client.switchTab('users'); page([contact('first')], true);
    const selected = [client.getSnapshot().directory.users[0].userId];
    client.searchContacts('second'); vi.advanceTimersByTime(250); page([contact('second')]);
    selected.push(client.getSnapshot().directory.users[0].userId);
    client.createGroup(selected);
    expect(socket.sent.at(-1)).toEqual({ type: 'createGroup', userIds: ['first', 'second'] });
  });
  it('renders offline contacts, existing unread and group selection, and separates empty/loading/error states', () => {
    client.switchTab('users'); page([contact('offline')]);
    socket.receive({ type: 'conversations', syncCursor: 1, conversations: [{ kind: 'user', id: 'offline', name: '伙伴 offline', unread: 7 }] });
    const props = { client, state: client.getSnapshot() };
    const sidebar = renderToStaticMarkup(<Sidebar {...props} onCreate={() => {}} onJoin={() => {}} onPassword={() => {}} />);
    expect(sidebar).toContain('离线 · offline_account'); expect(sidebar).toContain('class="unread">7');
    const group = renderToStaticMarkup(<GroupDialogs {...props} dialog="create" onClose={() => {}} />);
    expect(group).toContain('type="checkbox"'); expect(group).toContain('value="offline"'); expect(group).toContain('离线');
    client.searchContacts('missing');
    expect(renderToStaticMarkup(<ContactStatus client={client} state={client.getSnapshot()} />)).toContain('正在查找');
    vi.advanceTimersByTime(250); page([]);
    expect(renderToStaticMarkup(<ContactStatus client={client} state={client.getSnapshot()} />)).toContain('没有找到匹配');
    client.searchContacts('fail'); vi.advanceTimersByTime(250);
    socket.receive({ type: 'error', requestId: request().requestId as string, reason: '查询失败' });
    const error = renderToStaticMarkup(<ContactStatus client={client} state={client.getSnapshot()} />);
    expect(error).toContain('role="alert"'); expect(error).toContain('重试联系人查询');
  });
  it('sends the optional public display name without changing authentication fields', () => {
    socket.receive({ type: 'logout', success: true });
    client.register('new_user', 'private-password', '小林');
    expect(socket.sent.at(-1)).toMatchObject({ type: 'register', username: 'new_user', displayName: '小林' });
  });
});
