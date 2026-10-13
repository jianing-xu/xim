import { useEffect, useState, type FormEvent } from 'react';
import type { ChatClient } from '../chat-client';
import type { ChatState } from '../protocol';

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
      <span className="eyebrow">一个轻盈的聊天空间</span><h1>把对话<br />接上。</h1>
      <p>找到伙伴，聊一件小事。<br />也可以把大家聚在一个群里。</p>
      <div className="intro-note"><span className="line-mark" aria-hidden="true" />私聊与群聊，实时相见</div>
    </div>
    <section className="login-card" aria-labelledby="login-title">
      <span className="eyebrow">欢迎来到 XIM</span><h2 id="login-title">{register ? '创建你的账号' : '登录，开始聊天'}</h2>
      <p className="muted">{register ? '账号为 3–32 位字母、数字或下划线，区分大小写。密码为 8–128 个字符。' : '使用你的账号进入聊天空间。'}</p>
      <form id="login-form" onSubmit={login}>
        <label htmlFor="username">账号</label>
        <input id="username" name="username" autoComplete="username" placeholder="输入账号" required maxLength={32}
          minLength={3} pattern="[A-Za-z0-9_]{3,32}"
          value={username} onChange={event => setUsername(event.target.value)} />
        {register && <><label htmlFor="display-name">显示名（可选）</label>
          <input id="display-name" name="displayName" autoComplete="nickname" placeholder="默认显示账号名" maxLength={64}
            value={displayName} onChange={event => setDisplayName(event.target.value)} /></>}
        <label htmlFor="password">密码</label>
        <input id="password" name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} placeholder="输入密码" required maxLength={128} minLength={register ? 8 : 1}
          value={password} onChange={event => setPassword(event.target.value)} />
        <p id="login-error" className="form-error" role="alert">{state.accountError || state.loginError}</p>
        {state.accountNotice && <p role="status">{state.accountNotice}</p>}
        <button id="login-button" className="primary" type="submit" disabled={state.connection !== 'online' || state.loginPending || state.accountPending}>
          {state.loginPending || state.accountPending ? '正在处理…' : register ? '创建账号' : '登录'}
        </button>
      </form>
      <button id="register-toggle" className="join-button" type="button" disabled={state.loginPending || state.accountPending}
        onClick={() => { setRegister(!register); setPassword(''); client.clearAccountFeedback(); }}>
        {register ? '已有账号？返回登录' : '还没有账号？创建账号'}
      </button>
    </section>
  </main>;
}
