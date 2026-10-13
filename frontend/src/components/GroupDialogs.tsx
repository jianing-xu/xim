import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { ChatClient } from '../chat-client';
import type { ChatState, Contact } from '../protocol';
import { Avatar } from './Avatar';
import { ContactSearch, ContactStatus } from './ContactDirectory';

export type GroupDialog = 'create' | 'join' | 'leave' | null;

function Modal({ id, title, onClose, children }: { id: string; title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { const dialog = ref.current; dialog?.showModal(); return () => dialog?.close(); }, []);
  return <dialog id={id} ref={ref} aria-labelledby={`${id}-title`} onCancel={onClose}>
    <div className="dialog-header"><h2 id={`${id}-title`}>{title}</h2>
      <button type="button" className="icon-button" aria-label="关闭" onClick={onClose}>×</button>
    </div>{children}
  </dialog>;
}

export function GroupDialogs({ client, state, dialog, onClose }: {
  client: ChatClient; state: ChatState; dialog: GroupDialog; onClose: () => void;
}) {
  const [selected, setSelected] = useState<Contact[]>([]);
  const [groupId, setGroupId] = useState('');
  const users = state.directory.users.filter(user => user.userId !== state.session?.userId);
  useEffect(() => { if (dialog === 'create') { setSelected([]); client.browseContacts(); } }, [dialog, client]);
  const chat = state.conversations.find(item => item.key === state.current);
  return <>
    {dialog === 'create' && <Modal id="group-dialog" title="创建群聊" onClose={onClose}>
      <form id="group-form" onSubmit={event => {
        event.preventDefault();
        if (client.createGroup(selected.map(user => user.userId))) onClose();
      }}>
        <p className="muted">选择伙伴，离线也能加入。你会自动加入新群聊。</p>
        <ContactSearch id="group-contact-search" client={client} state={state} />
        <div className="selected-members" aria-label="已选择群成员">
          <span className="muted">已选择 {selected.length}/50 位</span>
          {selected.map(user => <button key={user.userId} type="button" className="text-button"
            aria-label={'取消选择 ' + user.displayName} onClick={() => setSelected(previous => previous.filter(item => item.userId !== user.userId))}>
            {user.displayName} ×
          </button>)}
        </div>
        <div id="group-options" className="group-options">
          {users.map(user => <label key={user.userId} className="group-choice">
            <input type="checkbox" name="member" value={user.userId} checked={selected.some(item => item.userId === user.userId)}
              disabled={selected.length >= 50 && !selected.some(item => item.userId === user.userId)}
              onChange={event => setSelected(previous => event.target.checked ? [...previous, user] : previous.filter(item => item.userId !== user.userId))} />
            <Avatar name={user.displayName} /><span>{user.displayName}<small className="contact-detail">
              {state.connection === 'online' && !state.restoring && user.online ? '在线' : '离线'} · {user.username}</small></span>
          </label>)}
          <ContactStatus client={client} state={state} />
        </div><button className="primary" type="submit" disabled={state.connection !== 'online' || state.restoring || state.syncing}>创建群聊</button>
      </form>
    </Modal>}
    {dialog === 'join' && <Modal id="join-dialog" title="加入群聊" onClose={onClose}>
      <form id="join-form" onSubmit={event => {
        event.preventDefault(); if (client.send({ type: 'joinGroup', groupId: groupId.trim() })) onClose();
      }}><label htmlFor="group-id">群号</label><input id="group-id" maxLength={64} placeholder="输入伙伴分享的群号" required
          value={groupId} onChange={event => setGroupId(event.target.value)} />
        <p className="muted">群号显示在群聊顶部。</p><button className="primary" type="submit">加入群聊</button>
      </form>
    </Modal>}
    {dialog === 'leave' && chat && <Modal id="leave-dialog" title="退出群聊？" onClose={onClose}>
      <form id="leave-form" onSubmit={event => {
        event.preventDefault(); if (client.send({ type: 'quitGroup', groupId: chat.id })) onClose();
      }}><p className="muted">退出后将不再收到这个群的消息，可以通过群号重新加入。</p>
        <button className="primary" type="submit">退出群聊</button>
      </form>
    </Modal>}
    {state.members && <Modal id="members-dialog" title="群成员" onClose={() => client.closeMembers()}>
      <div id="member-list" className="group-options">{state.members.map(member => <div className="member" key={member.userId}>
        <Avatar name={member.username} /><strong>{member.username}</strong><span className="mono">{member.userId}</span>
      </div>)}</div>
    </Modal>}
  </>;
}
