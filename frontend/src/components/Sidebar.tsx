import { useState } from 'react';
import type { ChatClient } from '../chat-client';
import type { ChatState } from '../protocol';
import { Avatar } from './Avatar';

export function Sidebar({ client, state, onCreate, onJoin }: {
  client: ChatClient; state: ChatState; onCreate: () => void; onJoin: () => void;
}) {
  const [search, setSearch] = useState('');
  const users = state.users.filter(user => user.userId !== state.session?.userId);
  const entries = state.tab === 'users'
    ? users.map(user => ({ kind: 'user' as const, id: user.userId, name: user.username,
      key: `user:${user.userId}`, messages: [], unread: 0 }))
    : [...state.conversations].reverse();
  const query = search.trim().toLowerCase();
  const filtered = entries.filter(chat => `${chat.name} ${chat.id}`.toLowerCase().includes(query));
  const session = state.session!;
  return <aside id="sidebar" className="sidebar" aria-label="会话与联系人">
    <div className="sidebar-title"><h2>消息</h2><button id="new-group" className="icon-button" title="创建群聊" aria-label="创建群聊" onClick={onCreate}>＋</button></div>
    <div className="tabs" role="tablist" aria-label="列表类型">
      <button id="conversations-tab" className={`tab${state.tab === 'conversations' ? ' active' : ''}`} role="tab"
        aria-selected={state.tab === 'conversations'} aria-controls="list" onClick={() => client.switchTab('conversations')}>会话</button>
      <button id="users-tab" className={`tab${state.tab === 'users' ? ' active' : ''}`} role="tab"
        aria-selected={state.tab === 'users'} aria-controls="list" onClick={() => client.switchTab('users')}>
        在线 <span id="online-count">{users.length}</span>
      </button>
    </div>
    <div className="search"><span aria-hidden="true">⌕</span><input id="search" type="search" placeholder="搜索联系人或群组"
      aria-label="搜索联系人或群组" value={search} onChange={event => setSearch(event.target.value)} /></div>
    <div id="list" className="conversation-list" role="tabpanel" aria-labelledby={state.tab === 'users' ? 'users-tab' : 'conversations-tab'}>
      {filtered.map(chat => <button key={chat.key} className={`conversation${state.current === chat.key ? ' selected' : ''}`}
        onClick={() => client.choose(chat.kind, chat.id, chat.name)}>
        <Avatar name={chat.name} group={chat.kind === 'group'} />
        <span className="conversation-copy"><strong>{chat.name}</strong><span className="preview">
          {state.tab === 'users' ? `在线 · ${chat.id}` : chat.messages.at(-1)?.text || (chat.kind === 'group' ? `群号 ${chat.id}` : '开始你们的对话')}
        </span></span>
        {chat.unread ? <span className="unread">{chat.unread > 99 ? '99+' : chat.unread}</span>
          : chat.kind === 'user' && state.users.some(user => user.userId === chat.id) ? <span className="presence" /> : null}
      </button>)}
      {!filtered.length && <p className="list-empty">{query ? '没有找到匹配的联系人或群组' : state.tab === 'users'
        ? <>还没有其他伙伴在线。<br />用另一个窗口登录，即可开始聊天。</>
        : <>还没有会话。<br />去在线列表找一个伙伴吧。</>}</p>}
    </div>
    <button id="join-group" className="join-button" onClick={onJoin}>通过群号加入群聊 <span aria-hidden="true">↗</span></button>
    <div className="profile"><Avatar id="self-avatar" name={session.username} /><div>
      <strong id="self-name">{session.username}</strong><span id="self-id" className="mono">{session.userId}</span>
    </div><button id="logout" className="icon-button" title="退出登录" aria-label="退出登录" onClick={() => client.send({ type: 'logout' })}>↪</button></div>
  </aside>;
}
