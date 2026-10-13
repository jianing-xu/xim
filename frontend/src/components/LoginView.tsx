import { useEffect, useState, type FormEvent } from 'react';
import type { ChatClient } from '../chat-client';
import type { ChatState } from '../protocol';
import { Icon } from './Icon';

function savedUsername() {
  try { return localStorage.getItem('xim.username') || ''; } catch { return ''; }
}

export function LoginView({ client, state }: { client: ChatClient; state: ChatState }) {
  const [username, setUsername] = useState(savedUsername);
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [register, setRegister] = useState(false);
  useEffect(() => { if (state.accountNotice.startsWith('注册成功')) setRegister(false); }, [state.accountNotice]);
  function login(event: FormEvent) {
    event.preventDefault();
    if ((register ? client.register(username, password, displayName) : client.login(username, password))) setPassword('');
  }
  return <main id="login-view" className="login-view">
    <div className="login-intro">
      <img className="intro-mark" src="/icon.svg" alt="" aria-hidden="true" />
      <div className="intro-copy">
        <span className="eyebrow">XIM · 即时通讯</span>
        <h1>沟通有序，<br />连接从容。</h1>
        <p>从一对一交流到群组讨论，<br />让每一次沟通都有清晰的归处。</p>
      </div>
      <div className="intro-features">
        <div><Icon name="message" /><span>私聊与群聊<small>连接彼此，交流想法</small></span></div>
        <div><Icon name="clock" /><span>消息历史<small>延续每一段对话</small></span></div>
      </div>
    </div>
    <div className="login-access">
      <section className="login-card" aria-labelledby="login-title">
        <span className="eyebrow">欢迎来到 XIM</span><h2 id="login-title">{register ? '创建你的账号' : '登录，开始聊天'}</h2>
        <p id="login-description" className="muted">{register ? '创建账号，与联系人开启对话。' : '使用你的账号进入沟通工作台。'}</p>
        <form id="login-form" onSubmit={login}>
          <label htmlFor="username">账号</label>
          <input id="username" name="username" autoComplete="username" placeholder="输入账号" required maxLength={32}
            minLength={3} pattern="[A-Za-z0-9_]{3,32}" aria-describedby={register ? 'username-hint' : undefined}
            value={username} onChange={event => setUsername(event.target.value)} />
          {register && <p id="username-hint" className="field-hint">3–32 位字母、数字或下划线，区分大小写。</p>}
          {register && <><label htmlFor="display-name">显示名（可选）</label>
            <input id="display-name" name="displayName" autoComplete="nickname" placeholder="默认显示账号名" maxLength={64}
              value={displayName} onChange={event => setDisplayName(event.target.value)} /></>}
          <label htmlFor="password">密码</label>
          <input id="password" name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} placeholder="输入密码" required maxLength={128} minLength={register ? 8 : 1}
            aria-describedby={register ? 'password-hint' : undefined} value={password} onChange={event => setPassword(event.target.value)} />
          {register && <p id="password-hint" className="field-hint">8–128 个字符。</p>}
          <p id="login-error" className="form-error" role="alert">{state.accountError || state.loginError}</p>
          {state.accountNotice && <p className="account-notice" role="status">{state.accountNotice}</p>}
          <button id="login-button" className="primary" type="submit" disabled={state.connection !== 'online' || state.loginPending || state.accountPending}>
            {state.loginPending || state.accountPending ? '正在处理…' : register ? '创建账号' : '登录'}<Icon name="arrow" size={18} />
          </button>
        </form>
        <button id="register-toggle" className="join-button" type="button" disabled={state.loginPending || state.accountPending}
          onClick={() => { setRegister(!register); setPassword(''); client.clearAccountFeedback(); }}>
          {register ? '已有账号？返回登录' : '还没有账号？创建账号'}
        </button>
      </section>
    </div>
  </main>;
}
