import { useEffect, useRef, useState } from 'react';
import type { ChatClient } from '../chat-client';
import type { ChatState } from '../protocol';
import { Icon } from './Icon';

export function PasswordDialog({ client, state, onClose }: { client: ChatClient; state: ChatState; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState('');
  useEffect(() => { ref.current?.showModal(); client.clearAccountFeedback(); return () => ref.current?.close(); }, [client]);
  return <dialog id="password-dialog" ref={ref} aria-labelledby="password-title" onCancel={onClose}>
    <div className="dialog-header"><h2 id="password-title">修改密码</h2>
      <button type="button" className="icon-button" aria-label="关闭" onClick={onClose}><Icon name="close" /></button></div>
    <form onSubmit={event => {
      event.preventDefault();
      if (next !== confirmation) { setError('两次新密码不一致'); return; }
      if (next === current) { setError('新密码必须与当前密码不同'); return; }
      setError('');
      if (client.changePassword(current, next)) { setCurrent(''); setNext(''); setConfirmation(''); }
    }}>
      <label htmlFor="current-password">当前密码</label>
      <input id="current-password" type="password" autoComplete="current-password" required maxLength={128} value={current} onChange={event => setCurrent(event.target.value)} />
      <label htmlFor="new-password">新密码（8–128 个字符）</label>
      <input id="new-password" type="password" autoComplete="new-password" required minLength={8} maxLength={128} value={next} onChange={event => setNext(event.target.value)} />
      <label htmlFor="confirm-password">确认新密码</label>
      <input id="confirm-password" type="password" autoComplete="new-password" required minLength={8} maxLength={128} value={confirmation} onChange={event => setConfirmation(event.target.value)} />
      <p className="muted">修改后需要重新登录，原有会话与消息历史会保留。</p>
      <p className="form-error" role="alert">{error || state.accountError}</p>
      <button className="primary" type="submit" disabled={state.accountPending || state.connection !== 'online'}>{state.accountPending ? '正在修改…' : '修改密码'}</button>
    </form>
  </dialog>;
}
