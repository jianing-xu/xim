import { useEffect, useState, useSyncExternalStore } from 'react';
import { ChatClient } from './chat-client';
import type { ChatState } from './protocol';
import { ChatPanel } from './components/ChatPanel';
import { GroupDialogs, type GroupDialog } from './components/GroupDialogs';
import { LoginView } from './components/LoginView';
import { Sidebar } from './components/Sidebar';

export default function App() {
  const [client] = useState(() => new ChatClient());
  const state = useSyncExternalStore(client.subscribe, client.getSnapshot);
  useEffect(() => { client.start(); return () => client.stop(); }, [client]);
  const connectionText = state.connection === 'online' ? '已连接' : state.connection === 'connecting' ? '正在连接' : '连接断开 · 正在重连';
  return <>
    <header className="brand-bar"><a className="brand" href="/" aria-label="XIM 首页">
      <span className="brand-mark" aria-hidden="true">x</span><span>XIM<span className="brand-caption">让对话发生</span></span>
    </a><span id="connection" className={`connection ${state.connection}`} role="status">{connectionText}</span></header>
    {state.session ? <ChatWorkspace key={state.session.userId} client={client} state={state} /> : <LoginView client={client} state={state} />}
    {state.toast && <div id="toast" className="toast" role="status">{state.toast}</div>}
  </>;
}

function ChatWorkspace({ client, state }: { client: ChatClient; state: ChatState }) {
  const [dialog, setDialog] = useState<GroupDialog>(null);
  return <main id="chat-view" className={`chat-shell${state.showChat ? ' show-chat' : ''}`}>
    <Sidebar client={client} state={state} onCreate={() => setDialog('create')} onJoin={() => setDialog('join')} />
    <ChatPanel client={client} state={state} onLeave={() => setDialog('leave')} />
    <GroupDialogs key={dialog || 'closed'} client={client} state={state} dialog={dialog} onClose={() => setDialog(null)} />
  </main>;
}
