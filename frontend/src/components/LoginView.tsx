import { useState, type FormEvent } from 'react';
import type { ChatClient } from '../chat-client';
import type { ChatState } from '../protocol';

function savedUsername() {
  try { return localStorage.getItem('xim.username') || ''; } catch { return ''; }
}

export function LoginView({ client, state }: { client: ChatClient; state: ChatState }) {
  const [username, setUsername] = useState(savedUsername);
  const [password, setPassword] = useState('');
  function login(event: FormEvent) {
    event.preventDefault();
    if (client.login(username, password)) setPassword('');
  }
  return <main id="login-view" className="login-view">
    <div className="login-intro">
      <span className="eyebrow">一个轻盈的聊天空间</span><h1>把对话<br />接上。</h1>
      <p>找到在线的伙伴，聊一件小事。<br />也可以把大家聚在一个群里。</p>
      <div className="intro-note"><span className="line-mark" aria-hidden="true" />私聊与群聊，实时相见</div>
    </div>
    <section className="login-card" aria-labelledby="login-title">
      <span className="eyebrow">欢迎来到 XIM</span><h2 id="login-title">登录，开始聊天</h2>
      <p className="muted">使用演示账号进入你的聊天空间。</p>
      <form id="login-form" onSubmit={login}>
        <label htmlFor="username">账号</label>
        <input id="username" name="username" autoComplete="username" placeholder="例如 root" required maxLength={64}
          value={username} onChange={event => setUsername(event.target.value)} />
        <label htmlFor="password">密码</label>
        <input id="password" name="password" type="password" autoComplete="current-password" placeholder="输入密码" required maxLength={128}
          value={password} onChange={event => setPassword(event.target.value)} />
        <p id="login-error" className="form-error" role="alert">{state.loginError}</p>
        <button id="login-button" className="primary" type="submit" disabled={state.connection !== 'online' || state.loginPending}>
          {state.loginPending ? '正在登录…' : '登录'}
        </button>
      </form>
      <div className="demo-note"><strong>可用的演示账号</strong><p>root / aaa / bbb / ccc</p><span>每个账号的密码与账号相同</span></div>
    </section>
  </main>;
}
