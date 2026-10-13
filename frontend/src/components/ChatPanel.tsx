import { useCallback, useEffect, useLayoutEffect, useRef, type FormEvent } from 'react';
import type { ChatClient } from '../chat-client';
import type { ChatState, Conversation } from '../protocol';
import { Avatar } from './Avatar';

const clock = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' });

export function ChatPanel({ client, state, onLeave }: { client: ChatClient; state: ChatState; onLeave: () => void }) {
  const chat = state.conversations.find(item => item.key === state.current);
  return <section className="chat-panel" aria-label="聊天内容">
    {chat ? <ActiveChat key={chat.key} client={client} state={state} chat={chat} onLeave={onLeave} />
      : <div id="empty-chat" className="empty-chat">
        <div className="empty-symbol" aria-hidden="true">↔</div><span className="eyebrow">对话从这里开始</span>
        <h2>发出你的第一句「你好」</h2><p>在联系人中选择一个伙伴，离线也能留言，<br />或创建群聊，邀请大家一起聊。</p>
        <button id="browse-users" className="secondary" onClick={() => client.switchTab('users')}>查找联系人 <span aria-hidden="true">→</span></button>
      </div>}
  </section>;
}

function ActiveChat({ client, state, chat, onLeave }: {
  client: ChatClient; state: ChatState; chat: Conversation; onLeave: () => void;
}) {
  const messages = useRef<HTMLDivElement>(null);
  const composer = useRef<HTMLTextAreaElement>(null);
  const followMessages = useRef(true);
  const online = client.isOnline(chat);
  const canSend = !state.restoring && !state.syncing && state.connection === 'online' && Boolean(chat.draft.trim());
  const reportReading = useCallback(() => {
    const list = messages.current;
    if (!list) return;
    const rect = list.getBoundingClientRect();
    const latest = list.lastElementChild?.getBoundingClientRect();
    const visible = rect.width > 0 && rect.height > 0 && Boolean(latest && latest.bottom > 0
      && latest.bottom <= Math.min(rect.bottom, innerHeight) + 8);
    client.reportReading(chat.key, visible, list.scrollHeight - list.scrollTop - list.clientHeight <= 8);
  }, [chat.key, client]);
  useLayoutEffect(() => {
    if (messages.current && followMessages.current) messages.current.scrollTop = messages.current.scrollHeight;
    reportReading();
  }, [chat.messages, chat.historyLoading, chat.historyLoaded, chat.readSequence, chat.unread, state.showChat, state.tab, state.session, state.syncing, state.restoring, reportReading]);
  useEffect(() => {
    document.addEventListener('visibilitychange', reportReading);
    window.addEventListener('resize', reportReading);
    return () => {
      document.removeEventListener('visibilitychange', reportReading);
      window.removeEventListener('resize', reportReading);
    };
  }, [reportReading]);
  useEffect(() => {
    if (matchMedia('(min-width:681px)').matches) composer.current?.focus();
  }, []);
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSend) return;
    followMessages.current = true;
    client.sendMessage();
    composer.current?.focus();
  }
  return <div id="active-chat" className="active-chat">
    <header className="chat-header">
      <button id="back" className="icon-button mobile-only" aria-label="返回会话列表" onClick={() => client.back()}>←</button>
      <Avatar id="chat-avatar" name={chat.name} group={chat.kind === 'group'} />
      <div className="chat-heading"><h2 id="chat-title">{chat.name}</h2><span id="chat-subtitle">
        {chat.kind === 'group' ? `群号 ${chat.id}` : `${online ? '在线' : '离线'} · ${chat.id}`}
      </span></div>
      {chat.kind === 'group' && <div id="group-actions" className="group-actions">
        <button id="members" className="text-button" onClick={() => client.send({ type: 'members', groupId: chat.id })}>成员</button>
        <button id="leave-group" className="text-button" onClick={onLeave}>退出群聊</button>
      </div>}
    </header>
    <div id="messages" className="messages" role="log" aria-live="polite" aria-label="消息列表" ref={messages}
      onScroll={() => { const list = messages.current; if (list) followMessages.current = list.scrollHeight - list.scrollTop - list.clientHeight < 80; reportReading(); }}>
      <button className="text-button" disabled={chat.historyLoading || (chat.historyLoaded && !chat.hasMore)}
        onClick={() => { followMessages.current = false; client.loadHistory(chat.key); }}>
        {chat.historyLoading ? '正在加载历史…' : chat.historyLoaded && !chat.hasMore ? '已显示全部历史' : '加载更早消息'}
      </button>
      {!chat.messages.length && <p className="message-empty">还没有消息，打个招呼吧。</p>}
      {chat.messages.map(message => <div key={message.id} className={`message-row${message.mine ? ' mine' : ''}`}>
        <Avatar name={message.name} /><div className="message-content">
          <div className="message-meta"><span>{message.mine ? '我' : message.name}</span><time dateTime={new Date(message.time).toISOString()}>{clock.format(message.time)}</time></div>
          <div className="bubble">{message.text}</div>
          {message.mine && message.status && <div className={`message-status${['已发送', '发送中…'].includes(message.status) ? '' : ' failed'}`}>{message.status}
            {message.retryable && message.clientMessageId && <button type="button" className="text-button"
              disabled={state.connection !== 'online' || state.restoring || state.syncing || !state.session}
              onClick={() => client.retryMessage(message.clientMessageId!)} aria-label="重试发送这条消息">重试</button>}
          </div>}
        </div>
      </div>)}
    </div>
    <form id="message-form" className="composer" onSubmit={submit}>
      <label className="sr-only" htmlFor="message">消息内容</label>
      <textarea id="message" ref={composer} placeholder="写一条消息…" maxLength={4000} rows={3} value={chat.draft}
        disabled={state.connection !== 'online' || state.restoring || state.syncing} onChange={event => client.setDraft(event.target.value)}
        onKeyDown={event => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229) {
            event.preventDefault(); event.currentTarget.form?.requestSubmit();
          }
        }} />
      <div className="composer-footer"><span id="compose-hint">{chat.kind === 'user' && !online ? '对方离线，消息将在登录后恢复' : 'Enter 发送 · Shift + Enter 换行'}</span>
        <button id="send" type="submit" className="primary" disabled={!canSend}>发送 <span aria-hidden="true">↗</span></button>
      </div>
    </form>
  </div>;
}
