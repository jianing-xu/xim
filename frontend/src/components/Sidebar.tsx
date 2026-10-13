import { positive, compareInt } from '../integers';
import { useState } from 'react';
import type { ChatClient } from '../chat-client';
import type { ChatState, ConversationSummary } from '../protocol';
import { Avatar } from './Avatar';
import { ContactSearch, ContactStatus } from './ContactDirectory';
import { Icon } from './Icon';

const activityTime = new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });

export function Sidebar({ client, state, onCreate, onJoin, onPassword }: {
  client: ChatClient; state: ChatState; onCreate: () => void; onJoin: () => void; onPassword: () => void;
}) {
  const [search, setSearch] = useState('');
  const users = state.users.filter(user => user.userId !== state.session?.userId);
  const entries: (ConversationSummary & { key: string })[] = state.tab === 'users'
    ? state.directory.users.filter(user => user.userId !== state.session?.userId).map(user => ({
      ...state.conversations.find(chat => chat.key === 'user:' + user.userId),
      kind: 'user' as const, id: user.userId, name: user.displayName, key: 'user:' + user.userId }))
    : state.conversations;
  const query = search.trim().toLowerCase();
  const filtered = state.tab === 'users' ? entries : entries.filter(chat => (chat.name + ' ' + chat.id).toLowerCase().includes(query));
  const session = state.session!;
  return <aside id="sidebar" className="sidebar" aria-label="会话与联系人">
    <div className="sidebar-title"><div><span className="section-label">沟通工作台</span><h2>消息</h2></div><button id="new-group" className="icon-button new-group" title="创建群聊" aria-label="创建群聊" onClick={onCreate}><Icon name="plus" /></button></div>
    <div className="tabs" role="tablist" aria-label="列表类型">
      <button id="conversations-tab" className={'tab' + (state.tab === 'conversations' ? ' active' : '')} role="tab"
        aria-selected={state.tab === 'conversations'} aria-controls="list" onClick={() => client.switchTab('conversations')}><Icon name="message" size={17} />会话</button>
      <button id="users-tab" className={'tab' + (state.tab === 'users' ? ' active' : '')} role="tab"
        aria-selected={state.tab === 'users'} aria-controls="list" onClick={() => client.switchTab('users')}>
        <Icon name="users" size={17} />联系人 <span id="online-count">{state.connection === 'online' && !state.restoring ? users.length : 0} 在线</span>
      </button>
    </div>
    {state.tab === 'users' ? <ContactSearch client={client} state={state} /> : <div className="search"><Icon name="search" size={18} />
      <input id="search" type="search" placeholder="搜索会话或群组" aria-label="搜索会话或群组" value={search}
        onChange={event => setSearch(event.target.value)} /></div>}
    <div id="list" className="conversation-list" role="tabpanel" aria-labelledby={state.tab === 'users' ? 'users-tab' : 'conversations-tab'}>
      {filtered.map(chat => <button key={chat.key} className={'conversation' + (state.current === chat.key ? ' selected' : '')}
        onClick={() => client.choose(chat.kind, chat.id, chat.name)}>
        <Avatar name={chat.name} group={chat.kind === 'group'} />
        <span className="conversation-copy"><strong>{chat.name}</strong><span className="preview">
          {state.tab === 'users'
            ? (client.isOnline(chat) ? '在线' : '离线') + ' · ' + state.directory.users.find(user => user.userId === chat.id)?.username
            : chat.lastMessage?.message || (chat.kind === 'group' ? '群号 ' + chat.id : '开始你们的对话')}
        </span></span>
        <span className="conversation-meta">
          {chat.lastTime ? <time dateTime={new Date(chat.lastTime).toISOString()}>{activityTime.format(chat.lastTime)}</time> : null}
          {positive(chat.unread) ? <span className="unread">{compareInt(chat.unread, 99) > 0 ? '99+' : chat.unread}</span>
            : chat.kind === 'user' && client.isOnline(chat) ? <span className="presence" /> : null}
        </span>
      </button>)}
      {state.tab === 'users' ? <ContactStatus client={client} state={state} /> : !filtered.length && <p className="list-empty">
        {query ? '没有找到匹配的会话或群组' : <>还没有会话。<br />去联系人中找一个伙伴吧。</>}
      </p>}
    </div>
    <div className="sidebar-actions">
      <button id="join-group" className="join-button" onClick={onJoin}><Icon name="users" size={18} />通过群号加入群聊 <Icon name="arrow" size={16} /></button>
      <div className="account-actions">
        <button id="change-password" className="text-button" onClick={onPassword}><Icon name="lock" size={14} />修改密码</button>
        <button id="revoke-sessions" className="text-button" onClick={() => client.revokeSessions()}>撤销登录凭证</button>
      </div>
    </div>
    <div className="profile"><Avatar id="self-avatar" name={session.username} /><div>
      <strong id="self-name">{session.username}</strong><span id="self-id" className="mono">{session.userId}</span>
    </div><button id="logout" className="icon-button" title="退出登录" aria-label="退出登录" onClick={() => client.logout()}><Icon name="logout" size={18} /></button></div>
  </aside>;
}
